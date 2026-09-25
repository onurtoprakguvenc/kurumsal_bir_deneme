package org.example.ingest;

import org.example.core.HeapGuard;
import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;
import org.example.ingest.OoxmlPackage.Relationship;
import org.example.ingest.OoxmlPackage.XmlCursor;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipException;

/**
 * Streaming XLSX extraction with the JDK only ({@link java.util.zip.ZipFile} + StAX). No DOM and no workbook
 * model: worksheets are pulled cell by cell and rendered as coordinate-aware Markdown blocks through
 * {@link TableBlockWriter}. Workbook-wide memory is limited to the shared-strings table (one compact character
 * buffer, capped by {@link Limits#maxSharedStringsBytes()}) and the number-format table.
 *
 * <p>Sheets follow workbook order ({@code xl/workbook.xml}) and are resolved through the package relationships.
 * Each sheet starts a new chunker page (page = 1-based sheet position) and a {@code === Sayfa: <name> ===}
 * boundary; rows carry {@code Row n} labels and columns their letters, so hits cite exact cells.</p>
 *
 * <p>Cells: shared strings ({@code t="s"}), inline strings ({@code <is>}, phonetic runs skipped), cached formula
 * results ({@code <v>}), booleans ({@code DOĞRU}/{@code YANLIŞ}), error codes, ISO dates ({@code t="d"}) and
 * numbers rendered with their number format in Turkish conventions ({@code 15.000,50 TL}, {@code %12,5},
 * {@code 05.03.2026}). A formula without a cached result is kept as {@code [Formül: SUM(B2:B10)]}.</p>
 */
final class XlsxTextExtractor {

    /** Extraction summary. */
    record Info(int sheets) {
    }

    private static final Set<String> MAIN_NAMESPACES = Set.of(
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
            "http://purl.oclc.org/ooxml/spreadsheetml/main");
    private static final String REL_NAMESPACE_TRANSITIONAL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String REL_NAMESPACE_STRICT = "http://purl.oclc.org/ooxml/officeDocument/relationships";
    private static final int MAX_FORMULA_CHARS = 4_096;
    private static final int MAX_SHARED_FORMULAS = 10_000;
    private static final String TEXT_LIMIT = "Belge metin sınırına ulaşıldı; kalan içerik dahil edilmedi.";

    private XlsxTextExtractor() {
    }

    static Info extract(Path file, TextChunker sink, Limits limits) throws IngestionException {
        try (OoxmlPackage pkg = OoxmlPackage.open(file, limits, "XLSX")) {
            String workbookPart = pkg.mainPart("xl/workbook.xml");
            if (!pkg.has(workbookPart)) {
                throw new IngestionException(Reason.CORRUPTED, "ZIP archive is not an Excel workbook (no workbook part)");
            }
            Workbook workbook = readWorkbook(pkg, workbookPart);
            Map<String, Relationship> rels = pkg.relationships(workbookPart);

            String sharedStringsPart = relatedPart(pkg, rels, "/sharedStrings", "xl/sharedStrings.xml");
            String stylesPart = relatedPart(pkg, rels, "/styles", "xl/styles.xml");
            SharedStrings strings = SharedStrings.EMPTY;
            if (sharedStringsPart != null) {
                if (pkg.declaredSize(sharedStringsPart) > limits.maxSharedStringsBytes()) {
                    throw new IngestionException(Reason.TOO_LARGE, "Shared strings table exceeds "
                            + (limits.maxSharedStringsBytes() >> 20) + " MB");
                }
                strings = SharedStrings.load(pkg, sharedStringsPart, limits);
            }
            NumberFormats formats = stylesPart == null ? NumberFormats.DEFAULT : NumberFormats.load(pkg, stylesPart);

            CellFormatter formatter = new CellFormatter(formats, workbook.date1904());
            TableBlockWriter writer = new TableBlockWriter(sink, limits);
            long[] cellBudget = {limits.maxCells()};
            int index = 0;
            for (SheetRef sheet : workbook.sheets()) {
                if (sink.truncated()) {
                    break;
                }
                index++;
                sink.startPage(index);
                Relationship rel = rels.get(sheet.relationshipId());
                SheetReader reader = new SheetReader(sheet, index, workbook.sheets().size(), strings, formatter,
                        writer, limits, cellBudget);
                if (rel == null || !pkg.has(rel.target())) {
                    reader.missing();
                    continue;
                }
                if (!reader.read(pkg, rel.target())) {
                    if (index < workbook.sheets().size()) {
                        sink.append("> Not: Sınıra ulaşıldığı için sonraki sayfalar dahil edilmedi.\n");
                    }
                    break;
                }
            }
            return new Info(index);
        } catch (XMLStreamException | IOException e) {
            throw classify(e);
        } catch (RuntimeException e) {
            throw new IngestionException(Reason.CORRUPTED, "Malformed workbook: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    /** Target of the first relationship of the given type, else the conventional part name if present. */
    private static String relatedPart(OoxmlPackage pkg, Map<String, Relationship> rels, String typeSuffix,
                                      String conventional) {
        for (Relationship rel : rels.values()) {
            if (rel.typeEndsWith(typeSuffix) && pkg.has(rel.target())) {
                return rel.target();
            }
        }
        return pkg.has(conventional) ? conventional : null;
    }

    /** The shared strings table outgrew its character cap or the heap while being loaded. */
    private static final class SharedStringsTooLarge extends IOException {
        private static final long serialVersionUID = 1L;

        SharedStringsTooLarge(String message) {
            super(message);
        }
    }

    private static IngestionException classify(Exception e) {
        String message = String.valueOf(e.getMessage()).replace('\n', ' ');
        if (e instanceof SharedStringsTooLarge) {
            return new IngestionException(message.startsWith("Heap") ? Reason.MEMORY_PRESSURE : Reason.TOO_LARGE,
                    message, e);
        }
        if (OoxmlGuards.budgetExceeded(e)) {
            return new IngestionException(Reason.TOO_LARGE, "Inflated workbook content exceeds the size limits "
                    + "(possible ZIP bomb)", e);
        }
        if (message.contains("nesting exceeds")) {
            return new IngestionException(Reason.CORRUPTED, "Workbook XML is nested too deeply", e);
        }
        if (e instanceof ZipException) {
            return new IngestionException(Reason.CORRUPTED, "Corrupted workbook archive: " + message, e);
        }
        return e instanceof XMLStreamException
                ? new IngestionException(Reason.CORRUPTED, "Malformed workbook XML: " + message, e)
                : new IngestionException(Reason.IO_ERROR, "Cannot read workbook: " + message, e);
    }

    // ============================================================================================ workbook

    private record SheetRef(String name, String relationshipId, boolean hidden) {
    }

    private record Workbook(List<SheetRef> sheets, boolean date1904) {
    }

    private static Workbook readWorkbook(OoxmlPackage pkg, String part) throws IOException, XMLStreamException {
        List<SheetRef> sheets = new ArrayList<>();
        boolean date1904 = false;
        try (XmlCursor cursor = pkg.open(part)) {
            XMLStreamReader r = cursor.reader();
            while (cursor.hasNext()) {
                if (cursor.next() != XMLStreamConstants.START_ELEMENT || !OoxmlPackage.in(MAIN_NAMESPACES, r.getNamespaceURI())) {
                    continue;
                }
                switch (r.getLocalName()) {
                    case "workbookPr" -> {
                        String value = r.getAttributeValue(null, "date1904");
                        date1904 = "1".equals(value) || "true".equalsIgnoreCase(value);
                    }
                    case "sheet" -> {
                        String id = r.getAttributeValue(REL_NAMESPACE_TRANSITIONAL, "id");
                        if (id == null) {
                            id = r.getAttributeValue(REL_NAMESPACE_STRICT, "id");
                        }
                        String state = r.getAttributeValue(null, "state");
                        sheets.add(new SheetRef(r.getAttributeValue(null, "name"), id,
                                "hidden".equals(state) || "veryHidden".equals(state)));
                    }
                    default -> {
                        // calcPr, definedNames, bookViews, … carry no content
                    }
                }
            }
        }
        return new Workbook(sheets, date1904);
    }

    /**
     * Shared strings in one character buffer plus an end-offset array: two objects instead of one String per
     * entry. Each entry is clipped to {@link Limits#maxCellChars()}.
     */
    private static final class SharedStrings {

        static final SharedStrings EMPTY = new SharedStrings();
        private static final long HEAP_CHECK_CHARS = 1_000_000;

        private final StringBuilder data = new StringBuilder();
        private int[] ends = new int[64];
        private int count;

        static SharedStrings load(OoxmlPackage pkg, String part, Limits limits) throws IOException, XMLStreamException {
            SharedStrings table = new SharedStrings();
            HeapGuard heap = new HeapGuard(limits.maxHeapUsageRatio());
            long nextHeapCheck = HEAP_CHECK_CHARS;
            try (XmlCursor cursor = pkg.open(part)) {
                XMLStreamReader r = cursor.reader();
                StringBuilder item = new StringBuilder();
                boolean inItem = false;
                boolean inText = false;
                int phonetic = 0;
                while (cursor.hasNext()) {
                    int event = cursor.next();
                    if (event == XMLStreamConstants.START_ELEMENT && OoxmlPackage.in(MAIN_NAMESPACES, r.getNamespaceURI())) {
                        switch (r.getLocalName()) {
                            case "si" -> {
                                inItem = true;
                                item.setLength(0);
                            }
                            case "rPh" -> phonetic++;
                            case "t" -> inText = inItem && phonetic == 0;
                            default -> {
                                // run properties are formatting only
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && OoxmlPackage.in(MAIN_NAMESPACES, r.getNamespaceURI())) {
                        switch (r.getLocalName()) {
                            case "si" -> {
                                table.add(item);
                                inItem = false;
                                // The declared part size can be absent (-1) or wrong, so the cap is enforced on what
                                // is actually held, and the heap is re-checked while the table grows.
                                if (table.data.length() > limits.maxSharedStringsBytes()) {
                                    throw new SharedStringsTooLarge("Shared strings table exceeds "
                                            + (limits.maxSharedStringsBytes() >> 20) + " MB");
                                }
                                if (table.data.length() >= nextHeapCheck) {
                                    nextHeapCheck = table.data.length() + HEAP_CHECK_CHARS;
                                    if (heap.underPressure()) {
                                        throw new SharedStringsTooLarge("Heap pressure while loading shared strings ("
                                                + heap.describe() + ")");
                                    }
                                }
                            }
                            case "rPh" -> phonetic = Math.max(0, phonetic - 1);
                            case "t" -> inText = false;
                            default -> {
                                // nothing to close
                            }
                        }
                    } else if (inText && isText(event)) {
                        appendBounded(item, r, limits.maxCellChars() + 1);
                    }
                }
            }
            return table;
        }

        private void add(CharSequence value) {
            if (count == ends.length) {
                ends = Arrays.copyOf(ends, count * 2);
            }
            data.append(value);
            ends[count++] = data.length();
        }

        String get(int index) {
            if (index < 0 || index >= count) {
                return null;
            }
            return data.substring(index == 0 ? 0 : ends[index - 1], ends[index]);
        }
    }

    /** Cell style → number format id → format code, from {@code xl/styles.xml}. */
    static final class NumberFormats {

        static final NumberFormats DEFAULT = new NumberFormats(new int[0], Map.of());

        /**
         * Built-in formats (ECMA-376 §18.8.30). Ids 5–8 are locale-dependent currency formats; they are mapped
         * to Turkish lira as Excel does on Turkish systems.
         */
        private static final Map<Integer, String> BUILT_IN = Map.ofEntries(
                Map.entry(0, "General"), Map.entry(1, "0"), Map.entry(2, "0.00"), Map.entry(3, "#,##0"),
                Map.entry(4, "#,##0.00"), Map.entry(5, "#,##0 \"₺\""), Map.entry(6, "#,##0 \"₺\""),
                Map.entry(7, "#,##0.00 \"₺\""), Map.entry(8, "#,##0.00 \"₺\""), Map.entry(9, "0%"),
                Map.entry(10, "0.00%"), Map.entry(11, "0.00E+00"), Map.entry(12, "# ?/?"), Map.entry(13, "# ??/??"),
                Map.entry(14, "dd.mm.yyyy"), Map.entry(15, "d-mmm-yy"), Map.entry(16, "d-mmm"),
                Map.entry(17, "mmm-yy"), Map.entry(18, "h:mm AM/PM"), Map.entry(19, "h:mm:ss AM/PM"),
                Map.entry(20, "h:mm"), Map.entry(21, "h:mm:ss"), Map.entry(22, "dd.mm.yyyy h:mm"),
                Map.entry(37, "#,##0 ;(#,##0)"), Map.entry(38, "#,##0 ;[Red](#,##0)"),
                Map.entry(39, "#,##0.00;(#,##0.00)"), Map.entry(40, "#,##0.00;[Red](#,##0.00)"),
                Map.entry(45, "mm:ss"), Map.entry(46, "[h]:mm:ss"), Map.entry(47, "mmss.0"),
                Map.entry(48, "##0.0E+0"), Map.entry(49, "@"));
        private static final int MAX_STYLES = 65_536;

        private final int[] styleFormatIds;
        private final Map<Integer, String> custom;

        /** Formats from another source (the XLS reader's XF and FORMAT records): cell style → format id → code. */
        static NumberFormats of(int[] styleFormatIds, Map<Integer, String> custom) {
            return new NumberFormats(styleFormatIds.clone(), Map.copyOf(custom));
        }

        private NumberFormats(int[] styleFormatIds, Map<Integer, String> custom) {
            this.styleFormatIds = styleFormatIds;
            this.custom = custom;
        }

        static NumberFormats load(OoxmlPackage pkg, String part) throws IOException, XMLStreamException {
            Map<Integer, String> custom = new HashMap<>();
            int[] ids = new int[64];
            int count = 0;
            try (XmlCursor cursor = pkg.open(part)) {
                XMLStreamReader r = cursor.reader();
                boolean inCellXfs = false;
                while (cursor.hasNext()) {
                    int event = cursor.next();
                    if (event == XMLStreamConstants.START_ELEMENT && OoxmlPackage.in(MAIN_NAMESPACES, r.getNamespaceURI())) {
                        switch (r.getLocalName()) {
                            case "numFmt" -> {
                                int id = parseInt(r.getAttributeValue(null, "numFmtId"), -1);
                                String code = r.getAttributeValue(null, "formatCode");
                                if (id >= 0 && code != null && custom.size() < MAX_STYLES) {
                                    custom.put(id, code);
                                }
                            }
                            case "cellXfs" -> inCellXfs = true;
                            case "xf" -> {
                                if (inCellXfs && count < MAX_STYLES) {
                                    if (count == ids.length) {
                                        ids = Arrays.copyOf(ids, count * 2);
                                    }
                                    ids[count++] = parseInt(r.getAttributeValue(null, "numFmtId"), 0);
                                }
                            }
                            default -> {
                                // fonts, fills, borders: presentation only
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && "cellXfs".equals(r.getLocalName())) {
                        inCellXfs = false;
                    }
                }
            }
            return new NumberFormats(Arrays.copyOf(ids, count), Map.copyOf(custom));
        }

        int formatId(int style) {
            return style >= 0 && style < styleFormatIds.length ? styleFormatIds[style] : 0;
        }

        String code(int formatId) {
            String code = custom.get(formatId);
            return code != null ? code : BUILT_IN.getOrDefault(formatId, "General");
        }
    }

    // ============================================================================================ worksheet

    /** Pulls one worksheet through a StAX cursor; memory holds one row and one table block. */
    private static final class SheetReader {

        private final SheetRef sheet;
        private final int index;
        private final int sheetCount;
        private final SharedStrings strings;
        private final CellFormatter formatter;
        private final TableBlockWriter writer;
        private final Limits limits;
        private final long[] cellBudget;
        private final List<String> notes = new ArrayList<>();
        private final Map<String, String> sharedFormulas = new HashMap<>();

        private String dimension;
        private boolean begun;
        private long lastRow;
        private long rowNumber;
        private long rows;
        private SortedMap<Integer, String> rowCells;
        private int nextColumn;
        private long droppedCells;
        private int placeholders;

        private int column;
        private String type;
        private int style;
        private boolean hasValue;
        private final StringBuilder value = new StringBuilder();
        private final StringBuilder formula = new StringBuilder();

        SheetReader(SheetRef sheet, int index, int sheetCount, SharedStrings strings, CellFormatter formatter,
                    TableBlockWriter writer, Limits limits, long[] cellBudget) {
            this.sheet = sheet;
            this.index = index;
            this.sheetCount = sheetCount;
            this.strings = strings;
            this.formatter = formatter;
            this.writer = writer;
            this.limits = limits;
            this.cellBudget = cellBudget;
        }

        void missing() {
            begin();
            notes.add("Sayfa verisi pakette bulunamadı.");
            writer.end(notes);
        }

        /** @return {@code false} when a document-wide limit stopped extraction */
        boolean read(OoxmlPackage pkg, String part) throws IOException, XMLStreamException {
            boolean keepGoing = true;
            boolean sheetDone = false;
            try (XmlCursor cursor = pkg.open(part)) {
                XMLStreamReader r = cursor.reader();
                while (keepGoing && !sheetDone && cursor.hasNext()) {
                    int event = cursor.next();
                    if (event != XMLStreamConstants.START_ELEMENT || !OoxmlPackage.in(MAIN_NAMESPACES, r.getNamespaceURI())) {
                        continue;
                    }
                    switch (r.getLocalName()) {
                        case "dimension" -> dimension = r.getAttributeValue(null, "ref");
                        case "sheetData" -> begin();
                        case "row" -> {
                            begin();
                            if (!flushRow()) {
                                notes.add(TEXT_LIMIT);
                                keepGoing = false;
                            } else if (++rows > limits.maxRowsPerTable()) {
                                notes.add(limits.maxRowsPerTable()
                                        + " satır sınırına ulaşıldı; sayfanın kalanı dahil edilmedi.");
                                sheetDone = true;
                            } else {
                                openRow(r.getAttributeValue(null, "r"));
                            }
                        }
                        case "c" -> {
                            String stop = readCell(cursor);
                            if (stop != null) {
                                notes.add(stop);
                                keepGoing = false;
                            }
                        }
                        case "mergeCells", "conditionalFormatting", "dataValidations", "extLst", "sheetViews",
                             "cols", "hyperlinks", "pageSetup", "headerFooter" -> cursor.skipElement();
                        default -> {
                            // a row ends when the next row starts or the worksheet ends
                        }
                    }
                }
                if (keepGoing && !flushRow()) {
                    notes.add(TEXT_LIMIT);
                    keepGoing = false;
                }
            }
            finish();
            return keepGoing;
        }

        private void begin() {
            if (begun) {
                return;
            }
            begun = true;
            String meta = "Çalışma sayfası " + index + "/" + sheetCount
                    + (dimension == null ? "" : " · aralık " + dimension)
                    + (sheet.hidden() ? " · gizli sayfa" : "");
            String title = sheet.name() == null || sheet.name().isBlank() ? Integer.toString(index) : sheet.name();
            writer.begin(title, meta, true);
        }

        private void openRow(String r) {
            long parsed = parseLong(r);
            rowNumber = parsed > 0 ? parsed : lastRow + 1;
            lastRow = rowNumber;
            rowCells = new TreeMap<>();
            nextColumn = 0;
        }

        private boolean flushRow() {
            if (rowCells == null) {
                return true;
            }
            SortedMap<Integer, String> cells = rowCells;
            rowCells = null;
            return writer.add(rowNumber, cells);
        }

        /** Reads one {@code <c>} element; returns a stop reason when a document-wide limit was hit. */
        private String readCell(XmlCursor cursor) throws XMLStreamException {
            XMLStreamReader r = cursor.reader();
            int parsed = columnIndex(r.getAttributeValue(null, "r"));
            column = parsed >= 0 ? parsed : nextColumn;
            nextColumn = column + 1;
            String t = r.getAttributeValue(null, "t");
            type = t == null ? "n" : t;
            style = parseInt(r.getAttributeValue(null, "s"), 0);
            value.setLength(0);
            formula.setLength(0);
            hasValue = false;

            boolean inValue = false;
            boolean inFormula = false;
            boolean inInline = false;
            boolean inText = false;
            int phonetic = 0;
            String formulaType = null;
            String sharedIndex = null;
            int level = 1;
            while (level > 0 && cursor.hasNext()) {
                int event = cursor.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    level++;
                    if (!OoxmlPackage.in(MAIN_NAMESPACES, r.getNamespaceURI())) {
                        continue;
                    }
                    switch (r.getLocalName()) {
                        case "v" -> {
                            inValue = true;
                            value.setLength(0);
                        }
                        case "f" -> {
                            inFormula = true;
                            formulaType = r.getAttributeValue(null, "t");
                            sharedIndex = r.getAttributeValue(null, "si");
                        }
                        case "is" -> {
                            inInline = true;
                            value.setLength(0);
                        }
                        case "rPh" -> phonetic++;
                        case "t" -> inText = inInline && phonetic == 0;
                        default -> {
                            // extLst and run properties inside a cell carry no value
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    level--;
                    if (level == 0 || !OoxmlPackage.in(MAIN_NAMESPACES, r.getNamespaceURI())) {
                        continue;
                    }
                    switch (r.getLocalName()) {
                        case "v" -> {
                            inValue = false;
                            hasValue = !value.isEmpty();
                        }
                        case "f" -> {
                            inFormula = false;
                            resolveSharedFormula(formulaType, sharedIndex);
                        }
                        case "t" -> inText = false;
                        case "rPh" -> phonetic = Math.max(0, phonetic - 1);
                        case "is" -> {
                            inInline = false;
                            hasValue = !value.isEmpty();
                        }
                        default -> {
                            // nothing to close
                        }
                    }
                } else if (isText(event)) {
                    if (inValue || inText) {
                        appendBounded(value, r, limits.maxCellChars() + 1);
                    } else if (inFormula) {
                        appendBounded(formula, r, MAX_FORMULA_CHARS);
                    }
                }
            }
            return store(render());
        }

        private void resolveSharedFormula(String formulaType, String sharedIndex) {
            if (!"shared".equals(formulaType) || sharedIndex == null) {
                return;
            }
            if (!formula.isEmpty()) {
                if (sharedFormulas.size() < MAX_SHARED_FORMULAS) {
                    sharedFormulas.put(sharedIndex, formula.toString());
                }
            } else {
                String master = sharedFormulas.get(sharedIndex);
                formula.append(master == null ? "paylaşılan formül #" + sharedIndex : master);
            }
        }

        private String store(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            if (rowCells == null) {
                rowCells = new TreeMap<>();
                rowNumber = lastRow + 1;
                lastRow = rowNumber;
            }
            if (rowCells.size() >= limits.maxColumns()) {
                droppedCells++;
                return null;
            }
            if (--cellBudget[0] < 0) {
                return limits.maxCells() + " hücre sınırına ulaşıldı; kalan içerik dahil edilmedi.";
            }
            rowCells.put(column, text);
            return null;
        }

        private String render() {
            String raw = value.toString();
            if (!formula.isEmpty() && !hasValue) {
                placeholders++;
                String text = formula.toString();
                return "[Formül: " + (text.startsWith("=") ? text.substring(1) : text) + "]";
            }
            return switch (type) {
                case "s" -> {
                    String shared = strings.get(parseInt(raw.strip(), -1));
                    yield shared != null ? shared : raw.isBlank() ? null : "[paylaşılan metin #" + raw.strip() + " yok]";
                }
                case "inlineStr", "str", "e" -> raw;
                case "b" -> "1".equals(raw.strip()) || "true".equalsIgnoreCase(raw.strip()) ? "DOĞRU" : "YANLIŞ";
                case "d" -> formatter.isoDate(raw);
                default -> {
                    if (raw.isBlank()) {
                        yield null;
                    }
                    try {
                        yield formatter.number(Double.parseDouble(raw.strip()), style);
                    } catch (NumberFormatException e) {
                        yield raw;
                    }
                }
            };
        }

        private void finish() {
            begin();
            if (placeholders > 0) {
                notes.add(placeholders + " formülün kayıtlı sonucu yok; [Formül: …] olarak bırakıldı.");
            }
            if (droppedCells > 0) {
                notes.add(droppedCells + " hücre " + limits.maxColumns() + " sütun sınırı nedeniyle dahil edilmedi.");
            }
            writer.end(notes);
        }
    }

    // ============================================================================================ helpers

    private static boolean isText(int event) {
        return event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                || event == XMLStreamConstants.SPACE;
    }

    private static void appendBounded(StringBuilder target, XMLStreamReader r, int max) {
        int room = max - target.length();
        if (room > 0) {
            target.append(r.getTextCharacters(), r.getTextStart(), Math.min(r.getTextLength(), room));
        }
    }

    /** Zero-based column of an A1 reference ({@code "AB12"} → 27), or -1 when absent. */
    static int columnIndex(String reference) {
        if (reference == null) {
            return -1;
        }
        int column = 0;
        int letters = 0;
        for (int i = 0; i < reference.length(); i++) {
            char c = Character.toUpperCase(reference.charAt(i));
            if (c >= 'A' && c <= 'Z') {
                column = column * 26 + (c - 'A' + 1);
                if (++letters > 3) {
                    return -1;
                }
            } else {
                break;
            }
        }
        return letters == 0 ? -1 : column - 1;
    }

    private static long parseLong(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseInt(String value, int fallback) {
        long parsed = parseLong(value);
        return parsed < 0 || parsed > Integer.MAX_VALUE ? fallback : (int) parsed;
    }

    // ============================================================================================ formatting

    /**
     * Renders numeric cell values with their Excel number format using Turkish conventions: '.' groups thousands,
     * ',' separates decimals, currency codes follow the amount, the percent sign precedes it.
     * "General" integers stay ungrouped so identifiers, years and phone numbers remain searchable verbatim.
     */
    static final class CellFormatter {

        private static final Locale TR = Locale.forLanguageTag("tr-TR");
        private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        private static final DateTimeFormatter DATE_TIME_SECONDS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
        private static final DateTimeFormatter TIME_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss");
        private static final Pattern GROUPING = Pattern.compile("[#0?],[#0?]");
        private static final double MAX_EXCEL_SERIAL = 2_958_465.0;

        enum Kind { GENERAL, NUMBER, PERCENT, SCIENTIFIC, DATE, TEXT }

        record NumberStyle(Kind kind, int decimals, boolean grouping, String currency, boolean hasDate,
                           boolean hasTime, boolean seconds, boolean elapsedHours) {
            static final NumberStyle GENERAL_STYLE = new NumberStyle(Kind.GENERAL, 0, false, null, false, false,
                    false, false);
        }

        private final NumberFormats formats;
        private final boolean date1904;
        private final Map<Integer, NumberStyle> cache = new HashMap<>();
        private final Map<Integer, DecimalFormat> decimalFormats = new HashMap<>();
        private final DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(TR);

        CellFormatter(NumberFormats formats, boolean date1904) {
            this.formats = formats;
            this.date1904 = date1904;
        }

        String number(double value, int styleIndex) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return Double.toString(value);
            }
            NumberStyle style = cache.computeIfAbsent(styleIndex, this::lookup);
            return switch (style.kind()) {
                case DATE -> date(value, style);
                case PERCENT -> {
                    String digits = decimal(Math.abs(value * 100), style.decimals(), style.grouping());
                    yield (value < 0 ? "-%" : "%") + digits;
                }
                case SCIENTIFIC -> String.format(TR, "%." + style.decimals() + "E", value);
                case NUMBER -> {
                    String digits = decimal(Math.abs(value), style.decimals(), style.grouping());
                    String signed = (value < 0 && !isZero(digits) ? "-" : "") + digits;
                    yield style.currency() == null ? signed : signed + " " + style.currency();
                }
                case GENERAL, TEXT -> {
                    String general = general(value);
                    yield style.currency() == null ? general : general + " " + style.currency();
                }
            };
        }

        String isoDate(String raw) {
            String v = raw.strip();
            try {
                if (v.length() <= 10) {
                    return LocalDate.parse(v).format(DATE);
                }
                LocalDateTime dt = LocalDateTime.parse(v.endsWith("Z") ? v.substring(0, v.length() - 1) : v);
                return dt.toLocalTime().equals(LocalTime.MIDNIGHT) ? dt.format(DATE) : dt.format(DATE_TIME);
            } catch (DateTimeParseException e) {
                return v;
            }
        }

        private NumberStyle lookup(int styleIndex) {
            int formatId = formats.formatId(styleIndex);
            return analyze(formatId, formats.code(formatId));
        }

        /** Classifies an Excel number format (first section only; the sign is rendered explicitly). */
        static NumberStyle analyze(int formatIndex, String format) {
            if (format == null || format.isBlank() || "general".equalsIgnoreCase(format.strip())) {
                return NumberStyle.GENERAL_STYLE;
            }
            String section = firstSection(format);
            String currency = currency(section);
            StringBuilder plain = new StringBuilder();
            boolean elapsedHours = false;
            for (int i = 0; i < section.length(); i++) {
                char c = section.charAt(i);
                if (c == '"') {
                    int close = section.indexOf('"', i + 1);
                    i = close < 0 ? section.length() : close;
                } else if (c == '\\' || c == '_' || c == '*') {
                    i++;
                } else if (c == '[') {
                    int close = section.indexOf(']', i + 1);
                    String inner = close < 0 ? "" : section.substring(i + 1, close).toLowerCase(Locale.ROOT);
                    if (inner.startsWith("h")) {
                        elapsedHours = true;
                        plain.append('h');
                    } else if (inner.startsWith("m") || inner.startsWith("s")) {
                        plain.append(inner.charAt(0));
                    }
                    i = close < 0 ? section.length() : close;
                } else {
                    plain.append(c);
                }
            }
            String p = plain.toString();
            String lower = p.toLowerCase(Locale.ROOT).replace("general", "");
            if (lower.contains("@")) {
                return new NumberStyle(Kind.TEXT, 0, false, currency, false, false, false, false);
            }
            boolean builtinDate = (formatIndex >= 14 && formatIndex <= 22) || (formatIndex >= 45 && formatIndex <= 47);
            boolean placeholders = lower.indexOf('0') >= 0 || lower.indexOf('#') >= 0 || lower.indexOf('?') >= 0;
            boolean dateLetters = lower.chars().anyMatch(ch -> ch == 'd' || ch == 'm' || ch == 'y' || ch == 'h'
                    || ch == 's');
            if (builtinDate || (dateLetters && !lower.contains("#") && !lower.contains("e+") && !lower.contains("e-"))) {
                boolean hasDate = lower.indexOf('d') >= 0 || lower.indexOf('y') >= 0
                        || (lower.indexOf('m') >= 0 && lower.indexOf('h') < 0 && lower.indexOf('s') < 0);
                boolean hasTime = lower.indexOf('h') >= 0 || lower.indexOf('s') >= 0;
                if (builtinDate && !hasDate && !hasTime) {
                    hasDate = true;
                }
                return new NumberStyle(Kind.DATE, 0, false, null, hasDate, hasTime, lower.indexOf('s') >= 0,
                        elapsedHours);
            }
            int decimals = decimals(p);
            boolean grouping = GROUPING.matcher(p).find();
            if (lower.contains("e+") || lower.contains("e-")) {
                return new NumberStyle(Kind.SCIENTIFIC, Math.max(decimals, 1), false, null, false, false, false, false);
            }
            if (p.indexOf('%') >= 0) {
                return new NumberStyle(Kind.PERCENT, decimals, grouping, null, false, false, false, false);
            }
            if (!placeholders) {
                return new NumberStyle(Kind.GENERAL, 0, false, currency, false, false, false, false);
            }
            return new NumberStyle(Kind.NUMBER, decimals, grouping || currency != null, currency, false, false,
                    false, false);
        }

        private static String firstSection(String format) {
            boolean quoted = false;
            for (int i = 0; i < format.length(); i++) {
                char c = format.charAt(i);
                if (c == '"') {
                    quoted = !quoted;
                } else if (c == '\\') {
                    i++;
                } else if (c == ';' && !quoted) {
                    return format.substring(0, i);
                }
            }
            return format;
        }

        private static String currency(String section) {
            String upper = section.toUpperCase(Locale.ROOT);
            if (section.contains("₺") || upper.contains("TL") || upper.contains("TRY")) {
                return "TL";
            }
            if (section.contains("€") || upper.contains("EUR")) {
                return "EUR";
            }
            if (section.contains("£") || upper.contains("GBP")) {
                return "GBP";
            }
            if (section.contains("$") || upper.contains("USD")) {
                return "USD";
            }
            return null;
        }

        private static int decimals(String plain) {
            int dot = -1;
            for (int i = 0; i < plain.length(); i++) {
                char c = plain.charAt(i);
                if (c == '.' && i + 1 < plain.length() && "0#?".indexOf(plain.charAt(i + 1)) >= 0) {
                    dot = i;
                    break;
                }
            }
            if (dot < 0) {
                return 0;
            }
            int count = 0;
            for (int i = dot + 1; i < plain.length() && "0#?".indexOf(plain.charAt(i)) >= 0; i++) {
                count++;
            }
            return Math.min(count, 15);
        }

        private String decimal(double absolute, int decimals, boolean grouping) {
            int key = decimals * 2 + (grouping ? 1 : 0);
            DecimalFormat format = decimalFormats.computeIfAbsent(key, k -> {
                DecimalFormat f = new DecimalFormat(grouping ? "#,##0" : "0", symbols);
                f.setMinimumFractionDigits(decimals);
                f.setMaximumFractionDigits(decimals);
                f.setRoundingMode(RoundingMode.HALF_UP);
                return f;
            });
            return format.format(absolute);
        }

        private String general(double value) {
            double absolute = Math.abs(value);
            if (absolute != 0 && (absolute >= 1e15 || absolute < 1e-9)) {
                return String.format(TR, "%.5E", value);
            }
            if (value == Math.rint(value)) {
                return Long.toString((long) value);
            }
            BigDecimal rounded = new BigDecimal(absolute).round(new MathContext(15, RoundingMode.HALF_UP))
                    .stripTrailingZeros();
            int scale = Math.max(0, rounded.scale());
            DecimalFormat format = new DecimalFormat(absolute >= 1_000 ? "#,##0" : "0", symbols);
            format.setMinimumFractionDigits(0);
            format.setMaximumFractionDigits(scale);
            format.setRoundingMode(RoundingMode.HALF_UP);
            return (value < 0 ? "-" : "") + format.format(rounded);
        }

        private String date(double serial, NumberStyle style) {
            if (serial < 0 || serial > MAX_EXCEL_SERIAL) {
                return general(serial);
            }
            long days = (long) Math.floor(serial);
            long seconds = Math.round((serial - days) * 86_400);
            if (seconds >= 86_400) {
                days++;
                seconds -= 86_400;
            }
            if (style.elapsedHours() && !style.hasDate()) {
                long total = Math.round(serial * 86_400);
                long h = total / 3_600;
                long m = (total % 3_600) / 60;
                long s = total % 60;
                return style.seconds() ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", h, m);
            }
            LocalDate base = date1904 ? LocalDate.of(1904, 1, 1)
                    : days < 60 ? LocalDate.of(1899, 12, 31) : LocalDate.of(1899, 12, 30);
            LocalDateTime dateTime = base.plusDays(days).atStartOfDay().plusSeconds(seconds);
            if (style.hasDate() && style.hasTime()) {
                return dateTime.format(style.seconds() ? DATE_TIME_SECONDS : DATE_TIME);
            }
            if (style.hasTime()) {
                return dateTime.format(style.seconds() ? TIME_SECONDS : TIME);
            }
            return dateTime.format(DATE);
        }

        private static boolean isZero(String digits) {
            for (int i = 0; i < digits.length(); i++) {
                char c = digits.charAt(i);
                if (c >= '1' && c <= '9') {
                    return false;
                }
            }
            return true;
        }
    }
}
