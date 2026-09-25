package org.example.ingest;

import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Streaming CSV/TSV extraction with pure JDK code.
 *
 * <ul>
 *   <li>Encoding: BOM, UTF-16 heuristic, strict UTF-8, then windows-1254 (see {@link TextEncoding}).</li>
 *   <li>Delimiter: Excel's {@code sep=;} hint if present, otherwise the candidate ({@code , ; TAB |}) that yields
 *       the most consistent field count over a quote-aware sample of the first records ({@code .tsv} prefers TAB).</li>
 *   <li>Parsing: RFC 4180 state machine; quoted fields may contain delimiters, doubled quotes and line breaks.
 *       Parsing is lenient (stray quotes are kept literally) and bounded: cells are clipped at
 *       {@link Limits#maxCellChars()}, surplus columns are counted but not stored, rows stop at
 *       {@link Limits#maxRowsPerTable()}.</li>
 *   <li>Output: coordinate-aware Markdown blocks via {@link TableBlockWriter}; {@code Row n} is the 1-based record
 *       number, so it matches the row shown when the file is opened in a spreadsheet. Values stay verbatim.</li>
 * </ul>
 */
final class CsvTextExtractor {

    /** Parse summary. */
    record Info(char delimiter, String charset, long records) {
    }

    private static final char[] CANDIDATES = {',', ';', '\t', '|'};
    private static final int SAMPLE_CHARS = 64 * 1024;
    private static final int SAMPLE_RECORDS = 50;

    private CsvTextExtractor() {
    }

    static Info extract(Path file, String displayName, TextChunker sink, Limits limits) throws IngestionException {
        TextEncoding.Detected detected = TextEncoding.sniff(file);
        boolean tsvHint = displayName.toLowerCase(Locale.ROOT).endsWith(".tsv");
        try {
            return parse(file, displayName, detected, detected.charset(), CodingErrorAction.REPORT, tsvHint, sink, limits);
        } catch (CharacterCodingException e) {
            sink.reset();
            try {
                return parse(file, displayName, detected, detected.retryCharset(), CodingErrorAction.REPLACE, tsvHint,
                        sink, limits);
            } catch (IOException e2) {
                throw new IngestionException(Reason.IO_ERROR, "Cannot read CSV: " + e2.getMessage(), e2);
            }
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot read CSV: " + e.getMessage(), e);
        }
    }

    private static Info parse(Path file, String displayName, TextEncoding.Detected detected, Charset charset,
                              CodingErrorAction onError, boolean tsvHint, TextChunker sink, Limits limits)
            throws IOException {
        try (Reader raw = TextEncoding.open(file, detected, charset, onError);
             BufferedReader in = new BufferedReader(raw, SAMPLE_CHARS * 2)) {
            in.mark(SAMPLE_CHARS + 1);
            char[] buffer = new char[SAMPLE_CHARS];
            int filled = 0;
            int n;
            while (filled < buffer.length && (n = in.read(buffer, filled, buffer.length - filled)) > 0) {
                filled += n;
            }
            in.reset();
            String sample = new String(buffer, 0, filled);

            Character hinted = null;
            if (sample.regionMatches(true, 0, "sep=", 0, 4) && sample.length() >= 5) {
                int lineEnd = sample.indexOf('\n');
                if (lineEnd > 0 && lineEnd <= 7) {
                    hinted = sample.charAt(4);
                    in.skip(lineEnd + 1L);
                    sample = sample.substring(lineEnd + 1);
                }
            }
            char delimiter = hinted != null ? hinted : detectDelimiter(sample, tsvHint);

            String meta = "Kaynak: " + (delimiter == '\t' ? "TSV" : "CSV") + " · ayraç: " + describe(delimiter)
                    + " · kodlama: " + charset.displayName();
            TableBlockWriter writer = new TableBlockWriter(sink, limits);
            writer.begin(displayName, meta, true);

            RecordReader reader = new RecordReader(in, delimiter, limits.maxCellChars(), limits.maxColumns());
            List<String> notes = new ArrayList<>();
            long record = 0;
            long cells = 0;
            List<String> fields;
            while ((fields = reader.next()) != null) {
                record++;
                if (record > limits.maxRowsPerTable()) {
                    notes.add(limits.maxRowsPerTable() + " satır sınırına ulaşıldı; kalan satırlar dahil edilmedi.");
                    record--;
                    break;
                }
                SortedMap<Integer, String> row = new TreeMap<>();
                for (int i = 0; i < fields.size(); i++) {
                    row.put(i, fields.get(i));
                }
                cells += row.size();
                if (cells > limits.maxCells()) {
                    notes.add(limits.maxCells() + " hücre sınırına ulaşıldı; kalan satırlar dahil edilmedi.");
                    break;
                }
                if (!writer.add(record, row)) {
                    notes.add("Belge metin sınırına ulaşıldı; kalan satırlar dahil edilmedi.");
                    break;
                }
            }
            if (reader.droppedColumns > 0) {
                notes.add("En geniş satırda " + reader.droppedColumns + " sütun " + limits.maxColumns()
                        + " sütun sınırını aştığı için dahil edilmedi.");
            }
            if (reader.clippedCells > 0) {
                notes.add(reader.clippedCells + " hücre " + limits.maxCellChars() + " karakterde kısaltıldı.");
            }
            if (reader.unclosedQuote) {
                notes.add("Dosya kapanmamış bir tırnak ile bitiyor; son alan dosya sonuna kadar okundu.");
            }
            writer.end(notes);
            return new Info(delimiter, charset.name(), record);
        }
    }

    /** Chooses the delimiter with the most consistent (&gt;1) field count across the sampled records. */
    static char detectDelimiter(String sample, boolean tsvHint) {
        char best = tsvHint ? '\t' : ',';
        long bestScore = 0;
        for (char candidate : CANDIDATES) {
            Map<Integer, Integer> frequency = new HashMap<>();
            int records = 0;
            int fields = 1;
            boolean quoted = false;
            boolean lineHasContent = false;
            for (int i = 0; i < sample.length() && records < SAMPLE_RECORDS; i++) {
                char c = sample.charAt(i);
                if (c == '"') {
                    quoted = !quoted;
                    lineHasContent = true;
                } else if (!quoted && c == candidate) {
                    fields++;
                    lineHasContent = true;
                } else if (!quoted && (c == '\n' || c == '\r')) {
                    if (lineHasContent) {
                        frequency.merge(fields, 1, Integer::sum);
                        records++;
                    }
                    fields = 1;
                    lineHasContent = false;
                } else if (!Character.isWhitespace(c)) {
                    lineHasContent = true;
                }
            }
            int modeFields = 0;
            int modeCount = 0;
            for (Map.Entry<Integer, Integer> e : frequency.entrySet()) {
                if (e.getKey() > 1 && (e.getValue() > modeCount || (e.getValue() == modeCount && e.getKey() > modeFields))) {
                    modeFields = e.getKey();
                    modeCount = e.getValue();
                }
            }
            long score = modeCount == 0 ? 0 : modeCount * 1_000L + modeFields;
            if (tsvHint && candidate == '\t' && score > 0) {
                return '\t';
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static String describe(char delimiter) {
        return switch (delimiter) {
            case ',' -> "virgül";
            case ';' -> "noktalı virgül";
            case '\t' -> "sekme";
            case '|' -> "dikey çizgi";
            default -> "'" + delimiter + "'";
        };
    }

    /** Bounded RFC 4180 record reader over its own char buffer (no per-char synchronization). */
    private static final class RecordReader {

        private final Reader in;
        private final char delimiter;
        private final int maxCellChars;
        private final int maxColumns;
        private final char[] buffer = new char[16 * 1024];
        private int position;
        private int length;
        private final StringBuilder field = new StringBuilder();
        private boolean fieldClipped;
        long clippedCells;
        long droppedColumns;
        boolean unclosedQuote;

        RecordReader(Reader in, char delimiter, int maxCellChars, int maxColumns) {
            this.in = in;
            this.delimiter = delimiter;
            this.maxCellChars = maxCellChars;
            this.maxColumns = maxColumns;
        }

        /** Next record, or {@code null} at end of input. */
        List<String> next() throws IOException {
            List<String> fields = new ArrayList<>();
            long fieldCount = 0;
            boolean quoted = false;
            boolean afterClosingQuote = false;
            boolean sawAnything = false;
            int c;
            while ((c = read()) >= 0) {
                sawAnything = true;
                if (quoted) {
                    if (c == '"') {
                        if (peek() == '"') {
                            read();
                            append('"');
                        } else {
                            quoted = false;
                            afterClosingQuote = true;
                        }
                    } else {
                        append((char) c);
                    }
                    continue;
                }
                if (c == delimiter) {
                    fieldCount = endField(fields, fieldCount);
                    afterClosingQuote = false;
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && peek() == '\n') {
                        read();
                    }
                    endField(fields, fieldCount);
                    return fields;
                } else if (c == '"' && field.isEmpty() && !afterClosingQuote) {
                    quoted = true;
                } else {
                    append((char) c);
                }
            }
            if (!sawAnything) {
                return null;
            }
            if (quoted) {
                unclosedQuote = true;
            }
            endField(fields, fieldCount);
            return fields;
        }

        private long endField(List<String> fields, long fieldCount) {
            if (fieldCount < maxColumns) {
                fields.add(fieldClipped ? field + "…" : field.toString());
            } else {
                droppedColumns = Math.max(droppedColumns, fieldCount + 1 - maxColumns);
            }
            if (fieldClipped) {
                clippedCells++;
            }
            field.setLength(0);
            fieldClipped = false;
            return fieldCount + 1;
        }

        private void append(char c) {
            if (field.length() < maxCellChars) {
                field.append(c);
            } else {
                fieldClipped = true;
            }
        }

        private int read() throws IOException {
            if (position == length && !fill()) {
                return -1;
            }
            return buffer[position++];
        }

        private int peek() throws IOException {
            if (position == length && !fill()) {
                return -1;
            }
            return buffer[position];
        }

        private boolean fill() throws IOException {
            int n = in.read(buffer, 0, buffer.length);
            if (n <= 0) {
                return false;
            }
            position = 0;
            length = n;
            return true;
        }
    }
}
