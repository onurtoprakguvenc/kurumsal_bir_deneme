package org.example.ingest;

import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;
import org.example.ingest.OoxmlPackage.Relationship;
import org.example.ingest.OoxmlPackage.XmlCursor;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;

/**
 * Streaming PPTX extraction with the JDK only ({@link java.util.zip.ZipFile} + StAX).
 *
 * <p>Slides are read in presentation order (the {@code p:sldIdLst} of {@code ppt/presentation.xml} resolved
 * through its relationships), which is the order the audience sees; when that list is missing, slide parts are
 * taken in natural numeric order ({@code slide2.xml} before {@code slide10.xml}). Every slide starts a new
 * chunker page (page = 1-based slide position) and a {@code === Slayt n ===} boundary, followed by:</p>
 * <ul>
 *   <li>{@code Başlık:} / {@code Alt başlık:} from title placeholders,</li>
 *   <li>body paragraphs, with {@code - } bullets and two-space indentation per outline level (body placeholders
 *       are bulleted unless {@code a:buNone}; free text boxes only with an explicit bullet),</li>
 *   <li>tables as Markdown ({@code Tablo:} + rows; merged continuation cells stay empty),</li>
 *   <li>SmartArt text from the slide's diagram data part, and speaker notes ({@code Konuşmacı notları:}).</li>
 * </ul>
 * <p>Styling, date/footer/slide-number placeholders and {@code mc:Fallback} duplicates are discarded.</p>
 */
final class PptxTextExtractor {

    /** Extraction summary. */
    record Info(int slides) {
    }

    private static final Set<String> PML = Set.of(
            "http://schemas.openxmlformats.org/presentationml/2006/main",
            "http://purl.oclc.org/ooxml/presentationml/main");
    private static final Set<String> DML = Set.of(
            "http://schemas.openxmlformats.org/drawingml/2006/main",
            "http://purl.oclc.org/ooxml/drawingml/main");
    private static final Set<String> REL_NAMESPACES = Set.of(
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "http://purl.oclc.org/ooxml/officeDocument/relationships");
    private static final String MARKUP_COMPATIBILITY = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final Pattern SLIDE_PART = Pattern.compile("ppt/slides/slide(\\d+)\\.xml");
    private static final Set<String> SKIPPED_PLACEHOLDERS = Set.of("dt", "ftr", "sldNum", "hdr", "sldImg");

    private PptxTextExtractor() {
    }

    static Info extract(Path file, TextChunker sink, Limits limits) throws IngestionException {
        try (OoxmlPackage pkg = OoxmlPackage.open(file, limits, "PPTX")) {
            String presentation = pkg.mainPart("ppt/presentation.xml");
            if (!pkg.has(presentation)) {
                throw new IngestionException(Reason.CORRUPTED, "ZIP archive is not a PowerPoint presentation");
            }
            List<String> slides = slideOrder(pkg, presentation);
            int number = 0;
            for (String slide : slides) {
                if (sink.truncated()) {
                    break;
                }
                if (number == limits.maxSlides()) {
                    sink.append("> Not: " + limits.maxSlides() + " slayt sınırına ulaşıldı; kalan "
                            + (slides.size() - number) + " slayt dahil edilmedi.\n");
                    break;
                }
                number++;
                sink.startPage(number);
                sink.appendPreformatted(renderSlide(pkg, slide, number, limits));
            }
            return new Info(number);
        } catch (XMLStreamException | IOException e) {
            throw classify(e);
        } catch (RuntimeException e) {
            throw new IngestionException(Reason.CORRUPTED, "Malformed presentation: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    private static IngestionException classify(Exception e) {
        String message = String.valueOf(e.getMessage()).replace('\n', ' ');
        if (OoxmlGuards.budgetExceeded(e)) {
            return new IngestionException(Reason.TOO_LARGE, "Inflated presentation content exceeds the size limits "
                    + "(possible ZIP bomb)", e);
        }
        if (message.contains("nesting exceeds")) {
            return new IngestionException(Reason.CORRUPTED, "Presentation XML is nested too deeply", e);
        }
        if (e instanceof ZipException) {
            return new IngestionException(Reason.CORRUPTED, "Corrupted presentation archive: " + message, e);
        }
        return e instanceof XMLStreamException
                ? new IngestionException(Reason.CORRUPTED, "Malformed presentation XML: " + message, e)
                : new IngestionException(Reason.IO_ERROR, "Cannot read presentation: " + message, e);
    }

    // ============================================================================================ slide order

    /** Presentation order from {@code p:sldIdLst}; natural part-name order as fallback. */
    private static List<String> slideOrder(OoxmlPackage pkg, String presentation)
            throws IOException, XMLStreamException {
        Map<String, Relationship> rels = pkg.relationships(presentation);
        List<String> ordered = new ArrayList<>();
        try (XmlCursor cursor = pkg.open(presentation)) {
            XMLStreamReader r = cursor.reader();
            while (cursor.hasNext()) {
                if (cursor.next() == XMLStreamConstants.START_ELEMENT && OoxmlPackage.in(PML, r.getNamespaceURI())
                        && "sldId".equals(r.getLocalName())) {
                    Relationship rel = rels.get(relationshipId(r));
                    if (rel != null && rel.typeEndsWith("/slide") && pkg.has(rel.target())
                            && !ordered.contains(rel.target())) {
                        ordered.add(rel.target());
                    }
                }
            }
        }
        if (!ordered.isEmpty()) {
            return ordered;
        }
        return pkg.partNames(name -> SLIDE_PART.matcher(name).matches()).stream()
                .sorted(Comparator.comparingLong(PptxTextExtractor::slideNumber))
                .toList();
    }

    private static long slideNumber(String part) {
        Matcher m = SLIDE_PART.matcher(part);
        if (!m.matches()) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static String relationshipId(XMLStreamReader r) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if ("id".equals(r.getAttributeLocalName(i)) && OoxmlPackage.in(REL_NAMESPACES, r.getAttributeNamespace(i))) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    // ============================================================================================ rendering

    private static CharSequence renderSlide(OoxmlPackage pkg, String part, int number, Limits limits)
            throws IOException, XMLStreamException {
        SlideContent slide = parse(pkg, part, limits);
        Output out = new Output(limits.maxSlideChars());
        out.line("=== Slayt " + number + " ===");
        if (slide.hidden) {
            out.line("(gizli slayt)");
        }
        int headerLines = out.lines;
        for (Shape shape : slide.shapes) {
            if ("title".equals(shape.placeholder) || "ctrTitle".equals(shape.placeholder)) {
                out.line("Başlık: " + joined(shape));
            }
        }
        for (Shape shape : slide.shapes) {
            if ("subTitle".equals(shape.placeholder)) {
                out.line("Alt başlık: " + joined(shape));
            }
        }
        for (Shape shape : slide.shapes) {
            if (shape.table != null) {
                renderTable(out, shape.table, limits);
            } else if (shape.placeholder == null || isBody(shape.placeholder)) {
                boolean bulletsByDefault = shape.placeholder != null;
                for (Paragraph p : shape.paragraphs) {
                    boolean bullet = p.bullet == null ? bulletsByDefault : p.bullet;
                    out.line((bullet ? "  ".repeat(p.level) + "- " : "") + p.text());
                }
            }
        }

        Map<String, Relationship> rels = pkg.relationships(part);
        for (Relationship rel : rels.values()) {
            if (rel.typeEndsWith("/diagramData") && pkg.has(rel.target())) {
                List<String> lines = plainParagraphs(pkg, rel.target(), limits);
                if (!lines.isEmpty()) {
                    out.line("SmartArt:");
                    lines.forEach(line -> out.line("- " + line));
                }
            }
        }
        if (out.lines == headerLines) {
            out.line("(metin içermiyor)");
        }
        for (Relationship rel : rels.values()) {
            if (rel.typeEndsWith("/notesSlide") && pkg.has(rel.target())) {
                List<String> notes = new ArrayList<>();
                for (Shape shape : parse(pkg, rel.target(), limits).shapes) {
                    if ("body".equals(shape.placeholder)) {
                        shape.paragraphs.forEach(p -> notes.add(p.text()));
                    }
                }
                if (!notes.isEmpty()) {
                    out.line("Konuşmacı notları:");
                    notes.forEach(out::line);
                }
            }
        }
        return out.finish();
    }

    private static boolean isBody(String placeholder) {
        return !SKIPPED_PLACEHOLDERS.contains(placeholder) && !"title".equals(placeholder)
                && !"ctrTitle".equals(placeholder) && !"subTitle".equals(placeholder);
    }

    private static String joined(Shape shape) {
        StringBuilder sb = new StringBuilder();
        for (Paragraph p : shape.paragraphs) {
            if (!sb.isEmpty()) {
                sb.append(" / ");
            }
            sb.append(p.text());
        }
        return sb.toString();
    }

    private static void renderTable(Output out, List<List<String>> rows, Limits limits) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) {
            return;
        }
        out.line("Tablo:");
        boolean first = true;
        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder("|");
            for (int c = 0; c < columns; c++) {
                String value = c < row.size() ? row.get(c) : "";
                line.append(' ').append(TableBlockWriter.oneLine(value, limits.maxCellChars()).replace("|", "\\|"))
                        .append(" |");
            }
            out.line(line.toString());
            if (first) {
                out.line("|" + " --- |".repeat(columns));
                first = false;
            }
        }
    }

    /** Bounded slide text builder; clipping is announced once. */
    private static final class Output {
        private final StringBuilder text = new StringBuilder(512);
        private final int max;
        private boolean clipped;
        int lines;

        Output(int max) {
            this.max = max;
        }

        void line(String value) {
            if (clipped) {
                return;
            }
            if (text.length() + value.length() + 1 > max) {
                clipped = true;
                text.append("> Not: Slayt metni ").append(max).append(" karakterde kısaltıldı.\n");
                return;
            }
            text.append(value).append('\n');
            lines++;
        }

        CharSequence finish() {
            return text.append('\n');
        }
    }

    // ============================================================================================ parsing

    private static final class Paragraph {
        final StringBuilder raw = new StringBuilder();
        int level;
        Boolean bullet;

        String text() {
            return TableBlockWriter.oneLine(raw.toString(), raw.length());
        }
    }

    private static final class Shape {
        String placeholder;
        final List<Paragraph> paragraphs = new ArrayList<>();
        List<List<String>> table;
    }

    private static final class SlideContent {
        boolean hidden;
        final List<Shape> shapes = new ArrayList<>();
    }

    /**
     * Walks a slide or notes part: {@code p:sp} shapes with their placeholder type and paragraphs, and
     * {@code a:tbl} tables inside graphic frames. Group shapes are flattened in document order.
     */
    private static SlideContent parse(OoxmlPackage pkg, String part, Limits limits)
            throws IOException, XMLStreamException {
        SlideContent slide = new SlideContent();
        int budget = limits.maxSlideChars();
        try (XmlCursor cursor = pkg.open(part)) {
            XMLStreamReader r = cursor.reader();
            Shape shape = null;
            Paragraph paragraph = null;
            List<List<String>> table = null;
            StringBuilder cell = null;
            boolean mergedCell = false;
            boolean inText = false;
            while (cursor.hasNext()) {
                int event = cursor.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String ns = r.getNamespaceURI();
                    String name = r.getLocalName();
                    if (MARKUP_COMPATIBILITY.equals(ns) && "Fallback".equals(name)) {
                        cursor.skipElement();
                    } else if (OoxmlPackage.in(PML, ns)) {
                        switch (name) {
                            case "sld" -> slide.hidden = "0".equals(r.getAttributeValue(null, "show"));
                            case "sp" -> shape = new Shape();
                            case "ph" -> {
                                if (shape != null) {
                                    String type = r.getAttributeValue(null, "type");
                                    shape.placeholder = type == null ? "obj" : type;
                                }
                            }
                            default -> {
                                // nvSpPr, spPr, style, … describe layout only
                            }
                        }
                    } else if (OoxmlPackage.in(DML, ns)) {
                        switch (name) {
                            case "tbl" -> table = new ArrayList<>();
                            case "tr" -> {
                                if (table != null) {
                                    table.add(new ArrayList<>());
                                }
                            }
                            case "tc" -> {
                                cell = new StringBuilder();
                                mergedCell = "1".equals(r.getAttributeValue(null, "hMerge"))
                                        || "1".equals(r.getAttributeValue(null, "vMerge"));
                            }
                            case "p" -> paragraph = new Paragraph();
                            case "pPr" -> {
                                if (paragraph != null) {
                                    paragraph.level = Math.min(8, Math.max(0,
                                            parseInt(r.getAttributeValue(null, "lvl"))));
                                }
                            }
                            case "buNone" -> {
                                if (paragraph != null) {
                                    paragraph.bullet = Boolean.FALSE;
                                }
                            }
                            case "buChar", "buAutoNum", "buBlip" -> {
                                if (paragraph != null) {
                                    paragraph.bullet = Boolean.TRUE;
                                }
                            }
                            case "br", "tab" -> {
                                if (paragraph != null) {
                                    paragraph.raw.append(' ');
                                }
                            }
                            case "t" -> inText = paragraph != null;
                            default -> {
                                // rPr, solidFill, latin, … are styling
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String ns = r.getNamespaceURI();
                    String name = r.getLocalName();
                    if (OoxmlPackage.in(PML, ns) && "sp".equals(name)) {
                        if (shape != null && !shape.paragraphs.isEmpty()) {
                            slide.shapes.add(shape);
                        }
                        shape = null;
                    } else if (OoxmlPackage.in(DML, ns)) {
                        switch (name) {
                            case "t" -> inText = false;
                            case "p" -> {
                                if (paragraph != null && !paragraph.raw.isEmpty() && !paragraph.raw.toString().isBlank()) {
                                    if (cell != null) {
                                        cell.append(cell.isEmpty() ? "" : " ").append(paragraph.raw);
                                    } else if (shape != null) {
                                        shape.paragraphs.add(paragraph);
                                    }
                                }
                                paragraph = null;
                            }
                            case "tc" -> {
                                if (table != null && !table.isEmpty() && cell != null) {
                                    table.getLast().add(mergedCell ? "" : cell.toString());
                                }
                                cell = null;
                            }
                            case "tbl" -> {
                                if (table != null && !table.isEmpty()) {
                                    Shape tableShape = new Shape();
                                    tableShape.table = table;
                                    slide.shapes.add(tableShape);
                                }
                                table = null;
                            }
                            default -> {
                                // nothing to close
                            }
                        }
                    }
                } else if (inText && paragraph != null && isText(event) && budget > 0) {
                    int take = Math.min(r.getTextLength(), budget);
                    paragraph.raw.append(r.getTextCharacters(), r.getTextStart(), take);
                    budget -= take;
                }
            }
        }
        return slide;
    }

    /** Paragraph texts of any DrawingML-bearing part (SmartArt data model). */
    private static List<String> plainParagraphs(OoxmlPackage pkg, String part, Limits limits)
            throws IOException, XMLStreamException {
        List<String> lines = new ArrayList<>();
        int budget = limits.maxSlideChars();
        try (XmlCursor cursor = pkg.open(part)) {
            XMLStreamReader r = cursor.reader();
            StringBuilder paragraph = null;
            boolean inText = false;
            while (cursor.hasNext()) {
                int event = cursor.next();
                if (event == XMLStreamConstants.START_ELEMENT && OoxmlPackage.in(DML, r.getNamespaceURI())) {
                    switch (r.getLocalName()) {
                        case "p" -> paragraph = new StringBuilder();
                        case "t" -> inText = paragraph != null;
                        default -> {
                            // styling
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && OoxmlPackage.in(DML, r.getNamespaceURI())) {
                    if ("t".equals(r.getLocalName())) {
                        inText = false;
                    } else if ("p".equals(r.getLocalName()) && paragraph != null) {
                        String text = TableBlockWriter.oneLine(paragraph.toString(), paragraph.length());
                        if (!text.isEmpty()) {
                            lines.add(text);
                        }
                        paragraph = null;
                    }
                } else if (inText && paragraph != null && isText(event) && budget > 0) {
                    int take = Math.min(r.getTextLength(), budget);
                    paragraph.append(r.getTextCharacters(), r.getTextStart(), take);
                    budget -= take;
                }
            }
        }
        return lines;
    }

    private static boolean isText(int event) {
        return event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                || event == XMLStreamConstants.SPACE;
    }

    private static int parseInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
