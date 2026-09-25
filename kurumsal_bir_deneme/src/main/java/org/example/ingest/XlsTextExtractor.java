package org.example.ingest;

import org.example.core.HeapGuard;
import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;
import org.example.ingest.PptTextExtractor.CompoundFile;
import org.example.ingest.XlsxTextExtractor.CellFormatter;
import org.example.ingest.XlsxTextExtractor.NumberFormats;

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Excel 97-2003 ({@code .xls}, BIFF8) extraction without third-party libraries, on the OLE2 container reader of
 * {@link PptTextExtractor}. Output matches the XLSX reader: every worksheet starts a page and becomes
 * coordinate-aware Markdown blocks through {@link TableBlockWriter} ({@code Row n}, column letters), and numbers and
 * dates are rendered with their cell formats in Turkish conventions by the same {@link CellFormatter}.
 *
 * <p>Read from the {@code Workbook} stream: the globals (shared strings {@code SST} with its {@code CONTINUE}
 * records, {@code FORMAT}/{@code XF} number formats, the 1904 date mode, {@code BOUNDSHEET} sheet names and
 * offsets), then each worksheet's cell records ({@code LABELSST}, {@code LABEL}, {@code NUMBER}, {@code RK},
 * {@code MULRK}, {@code BOOLERR}, and {@code FORMULA} with its cached result). Charts and macro sheets are skipped.
 * Records are read one at a time; the same limits as XLSX apply (rows, cells, columns, cell length, shared-strings
 * size). Password-protected workbooks and Excel 5/95 files are refused with a clear reason.</p>
 */
final class XlsTextExtractor {

    record Info(int sheets) {
    }

    private static final int BOF = 0x0809;
    private static final int EOF = 0x000A;
    private static final int FILEPASS = 0x002F;
    private static final int DATEMODE = 0x0022;
    private static final int FORMAT = 0x041E;
    private static final int XF = 0x00E0;
    private static final int BOUNDSHEET = 0x0085;
    private static final int SST = 0x00FC;
    private static final int CONTINUE = 0x003C;
    private static final int LABELSST = 0x00FD;
    private static final int LABEL = 0x0204;
    private static final int NUMBER = 0x0203;
    private static final int RK = 0x027E;
    private static final int MULRK = 0x00BD;
    private static final int BOOLERR = 0x0205;
    private static final int FORMULA = 0x0006;
    private static final int STRING = 0x0207;
    private static final int BIFF8 = 0x0600;
    private static final int SUBSTREAM_WORKSHEET = 0x0010;
    private static final String TEXT_LIMIT = "Belge metin sınırına ulaşıldı; kalan içerik dahil edilmedi.";

    private XlsTextExtractor() {
    }

    static Info extract(Path file, TextChunker sink, Limits limits) throws IngestionException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            CompoundFile cfb = CompoundFile.open(channel);
            CompoundFile.Entry book = cfb.find("Workbook");
            if (book == null) {
                if (cfb.find("Book") != null) {
                    throw new IngestionException(Reason.UNSUPPORTED,
                            "Excel 5.0/95 workbooks are not supported; open and save the file as .xlsx");
                }
                if (cfb.find("EncryptedPackage") != null) {
                    throw new IngestionException(Reason.ENCRYPTED, "Workbook is password protected");
                }
                throw new IngestionException(Reason.UNSUPPORTED, "OLE2 file is not an Excel 97-2003 workbook");
            }
            Records records = new Records(cfb.stream(book));
            Globals globals = Globals.read(records, limits);
            CellFormatter formatter = new CellFormatter(globals.formats(), globals.date1904());
            TableBlockWriter writer = new TableBlockWriter(sink, limits);
            long[] cellBudget = {limits.maxCells()};
            List<Sheet> sheets = globals.sheets().stream().filter(Sheet::worksheet).toList();
            int index = 0;
            for (Sheet sheet : sheets) {
                if (sink.truncated()) {
                    break;
                }
                index++;
                sink.startPage(index);
                boolean more = readSheet(records, sheet, index, sheets.size(), globals.strings(), formatter, writer,
                        limits, cellBudget);
                if (!more) {
                    if (index < sheets.size()) {
                        sink.append("> Not: Sınıra ulaşıldığı için sonraki sayfalar dahil edilmedi.\n");
                    }
                    break;
                }
            }
            return new Info(index);
        } catch (EOFException e) {
            throw new IngestionException(Reason.CORRUPTED, "Workbook stream is truncated", e);
        } catch (IOException e) {
            throw new IngestionException(Reason.CORRUPTED, "Unreadable workbook: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IngestionException(Reason.CORRUPTED, "Malformed workbook: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    // ================================================================== globals

    private record Sheet(String name, long offset, boolean hidden, boolean worksheet) {
    }

    private record Globals(List<String> strings, NumberFormats formats, boolean date1904, List<Sheet> sheets) {

        static Globals read(Records records, Limits limits) throws IOException, IngestionException {
            Record bof = records.at(0);
            if (bof == null || bof.type() != BOF || bof.length() < 4) {
                throw new IngestionException(Reason.CORRUPTED, "Workbook stream does not start with a BOF record");
            }
            if (u16(bof.data(), 0) != BIFF8) {
                throw new IngestionException(Reason.UNSUPPORTED,
                        "Only Excel 97-2003 (BIFF8) workbooks are supported; save the file as .xlsx");
            }
            List<String> strings = List.of();
            Map<Integer, String> custom = new HashMap<>();
            int[] xfFormats = new int[64];
            int xfCount = 0;
            boolean date1904 = false;
            List<Sheet> sheets = new ArrayList<>();
            Record r = records.next();
            while (r != null && r.type() != EOF) {
                byte[] d = r.data();
                switch (r.type()) {
                    case FILEPASS -> throw new IngestionException(Reason.ENCRYPTED, "Workbook is password protected");
                    case DATEMODE -> date1904 = d.length >= 2 && u16(d, 0) == 1;
                    case FORMAT -> {
                        if (d.length >= 5) {
                            custom.put(u16(d, 0), new Cursor(List.of(d), 2).unicodeString(512, false));
                        }
                    }
                    case XF -> {
                        if (d.length >= 4 && xfCount < 65_536) {
                            if (xfCount == xfFormats.length) {
                                xfFormats = Arrays.copyOf(xfFormats, xfCount * 2);
                            }
                            xfFormats[xfCount++] = u16(d, 2);
                        }
                    }
                    case BOUNDSHEET -> {
                        if (d.length >= 8) {
                            long offset = u32(d, 0);
                            boolean hidden = (d[4] & 0x03) != 0;
                            boolean worksheet = (d[5] & 0xFF) == 0;
                            String name = new Cursor(List.of(d), 6).shortString();
                            sheets.add(new Sheet(name, offset, hidden, worksheet));
                        }
                    }
                    case SST -> {
                        List<byte[]> segments = new ArrayList<>();
                        segments.add(d);
                        while (records.peekType() == CONTINUE) {
                            segments.add(records.next().data());
                        }
                        strings = readSst(segments, limits);
                    }
                    default -> {
                        // fonts, styles, names, palette … carry no cell text
                    }
                }
                r = records.next();
            }
            return new Globals(strings, NumberFormats.of(Arrays.copyOf(xfFormats, xfCount), custom), date1904, sheets);
        }

        private static List<String> readSst(List<byte[]> segments, Limits limits) throws IngestionException {
            Cursor c = new Cursor(segments, 0);
            long unique = c.u32at(4);
            c.skip(8);
            int max = limits.maxCellChars() + 1;
            List<String> out = new ArrayList<>((int) Math.min(unique, 1 << 16));
            long held = 0;
            HeapGuard heap = new HeapGuard(limits.maxHeapUsageRatio());
            for (long i = 0; i < unique && c.hasMore(); i++) {
                String s = c.richExtendedString(max);
                held += s.length();
                if (held > limits.maxSharedStringsBytes()) {
                    throw new IngestionException(Reason.TOO_LARGE, "Shared strings table exceeds "
                            + (limits.maxSharedStringsBytes() >> 20) + " MB");
                }
                if ((i & 0x3FFF) == 0 && heap.underPressure()) {
                    throw new IngestionException(Reason.MEMORY_PRESSURE, "Heap pressure while loading shared strings ("
                            + heap.describe() + ")");
                }
                out.add(s);
            }
            return out;
        }
    }

    // ================================================================== worksheets

    /** @return false when a document-wide limit stopped extraction */
    private static boolean readSheet(Records records, Sheet sheet, int index, int count, List<String> strings,
                                     CellFormatter formatter, TableBlockWriter writer, Limits limits,
                                     long[] cellBudget) throws IOException, IngestionException {
        List<String> notes = new ArrayList<>();
        String title = sheet.name() == null || sheet.name().isBlank() ? Integer.toString(index) : sheet.name();
        writer.begin(title, "Çalışma sayfası " + index + "/" + count + " · Excel 97-2003 (.xls)"
                + (sheet.hidden() ? " · gizli sayfa" : ""), true);
        Record bof = records.at(sheet.offset());
        if (bof == null || bof.type() != BOF || bof.length() < 4 || u16(bof.data(), 2) != SUBSTREAM_WORKSHEET) {
            notes.add("Sayfa verisi okunamadı.");
            writer.end(notes);
            return true;
        }
        SheetState st = new SheetState(writer, limits, cellBudget);
        boolean keepGoing = true;
        int depth = 1;
        int[] pendingFormula = null; // row, col awaiting its STRING record
        Record r = records.next();
        while (r != null && keepGoing) {
            int type = r.type();
            if (type == BOF) {
                depth++; // an embedded chart: skipped up to its EOF
            } else if (type == EOF) {
                if (--depth == 0) {
                    break;
                }
            } else if (depth == 1) {
                byte[] d = r.data();
                String stop = null;
                switch (type) {
                    case LABELSST -> {
                        if (d.length >= 10) {
                            int isst = (int) u32(d, 6);
                            stop = st.put(u16(d, 0), u16(d, 2), isst >= 0 && isst < strings.size()
                                    ? strings.get(isst) : "[paylaşılan metin #" + isst + " yok]");
                        }
                    }
                    case LABEL -> {
                        if (d.length >= 9) {
                            stop = st.put(u16(d, 0), u16(d, 2),
                                    new Cursor(List.of(d), 6).unicodeString(limits.maxCellChars() + 1, false));
                        }
                    }
                    case NUMBER -> {
                        if (d.length >= 14) {
                            stop = st.put(u16(d, 0), u16(d, 2),
                                    formatter.number(Double.longBitsToDouble(u64(d, 6)), u16(d, 4)));
                        }
                    }
                    case RK -> {
                        if (d.length >= 10) {
                            stop = st.put(u16(d, 0), u16(d, 2), formatter.number(rk((int) u32(d, 6)), u16(d, 4)));
                        }
                    }
                    case MULRK -> {
                        int row = u16(d, 0);
                        int first = u16(d, 2);
                        int n = (d.length - 6) / 6;
                        for (int k = 0; k < n && stop == null; k++) {
                            int at = 4 + k * 6;
                            stop = st.put(row, first + k, formatter.number(rk((int) u32(d, at + 2)), u16(d, at)));
                        }
                    }
                    case BOOLERR -> {
                        if (d.length >= 8) {
                            stop = st.put(u16(d, 0), u16(d, 2), d[7] != 0 ? error(d[6]) : d[6] != 0 ? "DOĞRU" : "YANLIŞ");
                        }
                    }
                    case FORMULA -> {
                        if (d.length >= 14) {
                            int row = u16(d, 0);
                            int col = u16(d, 2);
                            if ((d[12] & 0xFF) == 0xFF && (d[13] & 0xFF) == 0xFF) {
                                switch (d[6]) {
                                    case 0 -> pendingFormula = new int[]{row, col}; // text: in the next STRING record
                                    case 1 -> stop = st.put(row, col, d[8] != 0 ? "DOĞRU" : "YANLIŞ");
                                    case 2 -> stop = st.put(row, col, error(d[8]));
                                    default -> {
                                        // empty string result
                                    }
                                }
                            } else {
                                stop = st.put(row, col, formatter.number(Double.longBitsToDouble(u64(d, 6)), u16(d, 4)));
                            }
                        }
                    }
                    case STRING -> {
                        if (pendingFormula != null && d.length >= 3) {
                            List<byte[]> segments = new ArrayList<>();
                            segments.add(d);
                            while (records.peekType() == CONTINUE) {
                                segments.add(records.next().data());
                            }
                            stop = st.put(pendingFormula[0], pendingFormula[1],
                                    new Cursor(segments, 0).unicodeString(limits.maxCellChars() + 1, true));
                        }
                        pendingFormula = null;
                    }
                    default -> {
                        // row, column, merge, formatting and drawing records carry no cell text
                    }
                }
                if (stop != null) {
                    notes.add(stop);
                    if (st.rowLimitReached) {
                        break; // this sheet is done; the next sheets are still read
                    }
                    keepGoing = false; // cell or text limit: the rest of the workbook is left out
                }
            }
            r = records.next();
        }
        if (keepGoing && !st.rowLimitReached && !st.flush()) {
            notes.add(TEXT_LIMIT);
            keepGoing = false;
        }
        if (st.dropped > 0) {
            notes.add(st.dropped + " hücre " + limits.maxColumns() + " sütun sınırı nedeniyle dahil edilmedi.");
        }
        writer.end(notes);
        return keepGoing;
    }

    /** One worksheet's current row, flushed to the table writer when the next row starts. */
    private static final class SheetState {
        private final TableBlockWriter writer;
        /** Set when the per-sheet row limit ended this sheet (unlike document-wide limits). */
        boolean rowLimitReached;
        private final Limits limits;
        private final long[] cellBudget;
        private int row = -1;
        private long rows;
        private SortedMap<Integer, String> cells = new TreeMap<>();
        long dropped;

        SheetState(TableBlockWriter writer, Limits limits, long[] cellBudget) {
            this.writer = writer;
            this.limits = limits;
            this.cellBudget = cellBudget;
        }

        /** Stores a cell; returns a stop note when a limit ends this sheet or the document. */
        String put(int r, int col, String text) {
            if (r != row) {
                if (!flush()) {
                    return TEXT_LIMIT;
                }
                row = r;
                if (++rows > limits.maxRowsPerTable()) {
                    rowLimitReached = true;
                    row = -1;
                    return limits.maxRowsPerTable() + " satır sınırına ulaşıldı; sayfanın kalanı dahil edilmedi.";
                }
            }
            if (text == null || text.isBlank()) {
                return null;
            }
            if (cells.size() >= limits.maxColumns()) {
                dropped++;
                return null;
            }
            if (--cellBudget[0] < 0) {
                return limits.maxCells() + " hücre sınırına ulaşıldı; kalan içerik dahil edilmedi.";
            }
            cells.put(col, text);
            return null;
        }

        boolean flush() {
            if (row < 0 || cells.isEmpty()) {
                return true;
            }
            SortedMap<Integer, String> done = cells;
            cells = new TreeMap<>();
            return writer.add(row + 1L, done);
        }
    }

    private static double rk(int rk) {
        double value = (rk & 0x02) != 0 ? (double) (rk >> 2)
                : Double.longBitsToDouble(((long) (rk & 0xFFFFFFFC)) << 32);
        return (rk & 0x01) != 0 ? value / 100 : value;
    }

    private static String error(byte code) {
        return switch (code & 0xFF) {
            case 0x00 -> "#NULL!";
            case 0x07 -> "#SAYI/0!";
            case 0x0F -> "#DEĞER!";
            case 0x17 -> "#BAŞV!";
            case 0x1D -> "#AD?";
            case 0x24 -> "#SAYI!";
            case 0x2A -> "#YOK";
            default -> "#HATA";
        };
    }

    // ================================================================== record stream

    private record Record(int type, int length, byte[] data) {
    }

    /** Sequential BIFF record reader over the {@code Workbook} stream. */
    private static final class Records {
        private final CompoundFile.Stream stream;
        private final byte[] header = new byte[4];
        private long pos;

        Records(CompoundFile.Stream stream) {
            this.stream = stream;
        }

        /** Moves to {@code offset} and reads the record there. */
        Record at(long offset) throws IOException {
            pos = offset;
            return next();
        }

        Record next() throws IOException {
            if (pos + 4 > stream.size()) {
                return null;
            }
            stream.read(pos, header, 0, 4);
            int type = u16(header, 0);
            int length = u16(header, 2);
            if (pos + 4 + length > stream.size()) {
                throw new EOFException("record 0x" + Integer.toHexString(type) + " runs past the stream");
            }
            byte[] data = new byte[length];
            stream.read(pos + 4, data, 0, length);
            pos += 4 + length;
            return new Record(type, length, data);
        }

        int peekType() throws IOException {
            if (pos + 4 > stream.size()) {
                return -1;
            }
            stream.read(pos, header, 0, 4);
            return u16(header, 0);
        }
    }

    /**
     * Reads across a record and its {@code CONTINUE} records. Plain fields continue byte for byte; a character array
     * that is split restarts with a one-byte flag giving the new character width.
     */
    private static final class Cursor {
        private final List<byte[]> segments;
        private int segment;
        private int pos;

        Cursor(List<byte[]> segments, int start) {
            this.segments = segments;
            this.pos = start;
        }

        boolean hasMore() {
            while (segment < segments.size() && pos >= segments.get(segment).length) {
                if (segment + 1 >= segments.size()) {
                    return false;
                }
                segment++;
                pos = 0;
            }
            return segment < segments.size();
        }

        long u32at(int offset) {
            byte[] first = segments.getFirst();
            return XlsTextExtractor.u32(first, offset);
        }

        int u8() {
            if (!hasMore()) {
                throw new IllegalStateException("string data ends early");
            }
            return segments.get(segment)[pos++] & 0xFF;
        }

        int u16() {
            return u8() | u8() << 8;
        }

        long u32() {
            return Integer.toUnsignedLong(u16() | u16() << 16);
        }

        void skip(long n) {
            for (long i = 0; i < n; i++) {
                u8();
            }
        }

        /** ShortXLUnicodeString: 8-bit length, flags, characters (sheet names). */
        String shortString() {
            int cch = u8();
            boolean wide = (u8() & 0x01) != 0;
            return chars(cch, wide, 256);
        }

        /**
         * XLUnicodeString (LABEL, FORMAT, STRING): 16-bit length, flags, characters. {@code split} allows the
         * characters to continue in a CONTINUE record.
         */
        String unicodeString(int max, boolean split) {
            int cch = u16();
            boolean wide = (u8() & 0x01) != 0;
            return chars(cch, wide, max);
        }

        /** XLUnicodeRichExtendedString (SST entries): optional rich-text runs and phonetic block are skipped. */
        String richExtendedString(int max) {
            int cch = u16();
            int flags = u8();
            boolean wide = (flags & 0x01) != 0;
            int runs = (flags & 0x08) != 0 ? u16() : 0;
            long ext = (flags & 0x04) != 0 ? u32() : 0;
            String s = chars(cch, wide, max);
            skip(runs * 4L);
            skip(ext);
            return s;
        }

        private String chars(int cch, boolean wide, int max) {
            StringBuilder sb = new StringBuilder(Math.min(cch, max));
            int left = cch;
            while (left > 0) {
                if (segment < segments.size() && pos >= segments.get(segment).length) {
                    if (segment + 1 >= segments.size()) {
                        break; // truncated: keep what was read
                    }
                    segment++;
                    pos = 0;
                    wide = (u8() & 0x01) != 0; // a split character array restarts with its width flag
                }
                byte[] seg = segments.get(segment);
                int available = (seg.length - pos) / (wide ? 2 : 1);
                if (available == 0) {
                    pos = seg.length; // a dangling half character: move to the next segment
                    continue;
                }
                int take = Math.min(left, available);
                if (sb.length() < max) {
                    int keep = Math.min(take, max - sb.length());
                    sb.append(wide ? new String(seg, pos, keep * 2, StandardCharsets.UTF_16LE)
                            : new String(seg, pos, keep, StandardCharsets.ISO_8859_1));
                }
                pos += take * (wide ? 2 : 1);
                left -= take;
            }
            return sb.toString();
        }
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] b, int off) {
        return Integer.toUnsignedLong(u16(b, off) | u16(b, off + 2) << 16);
    }

    private static long u64(byte[] b, int off) {
        return u32(b, off) | u32(b, off + 4) << 32;
    }
}
