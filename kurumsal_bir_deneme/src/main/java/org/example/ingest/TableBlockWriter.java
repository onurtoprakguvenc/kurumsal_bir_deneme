package org.example.ingest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeSet;

/**
 * Renders tabular rows as coordinate-aware Markdown into the {@link TextChunker}.
 *
 * <p>Rows are buffered in small blocks (rendered size about {@link Limits#tableBlockChars()} characters, at most
 * {@value #MAX_BLOCK_ROWS} rows), so memory stays constant however large the sheet is. Every block is a complete,
 * column-aligned Markdown table with its own coordinate header and closes its chunk, so each index chunk is
 * self-describing:</p>
 * <pre>
 * | Row    | A: Kalem     | B: Tutar       |
 * |--------|--------------|----------------|
 * | Row 14 | Kira bedeli  | 15.000,50 TL   |
 * </pre>
 * <p>The first non-empty row becomes the column names when it looks like a header (at least two cells, every
 * cell textual and short); it is then announced once and not repeated as data.</p>
 */
final class TableBlockWriter {

    static final String SHEET_PREFIX = "=== Sayfa: ";
    private static final int MAX_BLOCK_ROWS = 40;
    private static final int MAX_PAD = 24;
    private static final int MAX_HEADER_NAME = 40;
    /** Room kept free so closing notes still fit once the document character limit is near. */
    private static final int NOTE_RESERVE = 512;

    private record Row(long number, SortedMap<Integer, String> cells) {
    }

    private final TextChunker sink;
    private final Limits limits;
    private final List<Row> block = new ArrayList<>();
    private final Map<Integer, Integer> blockWidths = new HashMap<>();
    private int blockChars;
    private boolean detectHeader;
    private boolean headerDecided;
    private Map<Integer, String> headerNames = Map.of();
    private long rows;
    private boolean exhausted;

    TableBlockWriter(TextChunker sink, Limits limits) {
        this.sink = sink;
        this.limits = limits;
    }

    /** Starts a new table (sheet or CSV file) with its boundary line and an optional metadata line. */
    void begin(String title, String meta, boolean detectHeaderRow) {
        block.clear();
        blockWidths.clear();
        blockChars = 0;
        headerNames = Map.of();
        headerDecided = false;
        detectHeader = detectHeaderRow;
        rows = 0;
        sink.append(SHEET_PREFIX + oneLine(title, 200) + " ===\n");
        if (meta != null && !meta.isBlank()) {
            sink.append(meta + "\n");
        }
        sink.append("\n");
    }

    /**
     * Adds one row. {@code cells} maps zero-based column index to raw cell text; blank cells are ignored.
     *
     * @return {@code false} once the document character limit is reached; the caller should stop and report it
     */
    boolean add(long rowNumber, SortedMap<Integer, String> cells) {
        if (exhausted || sink.truncated()) {
            return false;
        }
        cells.values().removeIf(v -> v == null || v.isBlank());
        if (cells.isEmpty()) {
            return true;
        }
        if (detectHeader && !headerDecided) {
            headerDecided = true;
            if (looksLikeHeader(cells)) {
                Map<Integer, String> names = new LinkedHashMap<>();
                cells.forEach((col, value) -> names.put(col, oneLine(value, MAX_HEADER_NAME)));
                headerNames = names;
                sink.append("Başlık satırı: Row " + rowNumber + " (sütun adları tablo başlığında)\n\n");
                return true;
            }
        }
        headerDecided = true;
        block.add(new Row(rowNumber, cells));
        rows++;
        cells.forEach((col, value) -> blockWidths.merge(col,
                Math.min(MAX_PAD, Math.max(labelWidth(col), Math.min(value.length(), limits.maxCellChars()))),
                Math::max));
        int lineWidth = ("Row " + rowNumber).length() + 4;
        int shown = 0;
        for (int width : blockWidths.values()) {
            if (shown++ >= limits.maxColumns()) {
                break;
            }
            lineWidth += width + 3;
        }
        blockChars = lineWidth * (block.size() + 2);
        if (blockChars >= limits.tableBlockChars() || block.size() >= MAX_BLOCK_ROWS) {
            flush(true);
        }
        return !exhausted && !sink.truncated();
    }

    /** Flushes the last block and appends closing notes (limits hit, formulas skipped, …). */
    void end(List<String> notes) {
        flush(false);
        if (rows == 0) {
            sink.append("(boş sayfa)\n");
        }
        for (String note : notes) {
            sink.append("> Not: " + note + "\n");
        }
        sink.append("\n");
        sink.endChunk();
    }

    /** Renders the buffered block; {@code closeChunk} ends the chunk right after it. */
    private void flush(boolean closeChunk) {
        if (block.isEmpty()) {
            return;
        }
        TreeSet<Integer> used = new TreeSet<>();
        for (Row row : block) {
            used.addAll(row.cells().keySet());
        }
        List<Integer> columns = new ArrayList<>(used);
        int omitted = 0;
        if (columns.size() > limits.maxColumns()) {
            omitted = columns.size() - limits.maxColumns();
            columns = columns.subList(0, limits.maxColumns());
        }

        String[] labels = new String[columns.size()];
        int[] widths = new int[columns.size()];
        String[][] cells = new String[block.size()][columns.size()];
        String[] rowLabels = new String[block.size()];
        int rowWidth = "Row".length();
        for (int r = 0; r < block.size(); r++) {
            rowLabels[r] = "Row " + block.get(r).number();
            rowWidth = Math.max(rowWidth, rowLabels[r].length());
        }
        for (int c = 0; c < columns.size(); c++) {
            int col = columns.get(c);
            String name = headerNames.get(col);
            labels[c] = columnName(col) + (name == null ? "" : ": " + escape(name));
            widths[c] = width(labels[c]);
            for (int r = 0; r < block.size(); r++) {
                String value = block.get(r).cells().get(col);
                cells[r][c] = value == null ? "" : escape(oneLine(value, limits.maxCellChars()));
                widths[c] = Math.max(widths[c], width(cells[r][c]));
            }
            widths[c] = Math.min(widths[c], MAX_PAD);
        }

        StringBuilder sb = new StringBuilder(blockChars * 2 + 256);
        sb.append("| ").append(pad("Row", rowWidth));
        for (int c = 0; c < columns.size(); c++) {
            sb.append(" | ").append(pad(labels[c], widths[c]));
        }
        sb.append(" |\n|").append("-".repeat(rowWidth + 2));
        for (int width : widths) {
            sb.append('|').append("-".repeat(width + 2));
        }
        sb.append("|\n");
        for (int r = 0; r < block.size(); r++) {
            sb.append("| ").append(pad(rowLabels[r], rowWidth));
            for (int c = 0; c < columns.size(); c++) {
                sb.append(" | ").append(pad(cells[r][c], widths[c]));
            }
            sb.append(" |\n");
        }
        if (omitted > 0) {
            sb.append("(+").append(omitted).append(" sütun bu blokta gösterilmedi; sınır ")
                    .append(limits.maxColumns()).append(")\n");
        }
        sb.append('\n');
        if (sb.length() + NOTE_RESERVE > sink.remainingChars()) {
            // Never cut a table mid-row: drop the whole block and let the caller report the limit.
            exhausted = true;
            block.clear();
            blockWidths.clear();
            blockChars = 0;
            return;
        }
        sink.appendPreformatted(sb);
        if (closeChunk) {
            sink.endChunk();
        }
        block.clear();
        blockWidths.clear();
        blockChars = 0;
    }

    private int labelWidth(int column) {
        String name = headerNames.get(column);
        return columnName(column).length() + (name == null ? 0 : 2 + name.length());
    }

    /** Spreadsheet column name for a zero-based index: 0 → A, 25 → Z, 26 → AA. */
    static String columnName(int index) {
        StringBuilder sb = new StringBuilder(3);
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.append((char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.reverse().toString();
    }

    /** Collapses all whitespace (including line breaks inside cells) and clips to {@code max} characters. */
    static String oneLine(String value, int max) {
        StringBuilder sb = new StringBuilder(Math.min(value.length(), max) + 1);
        boolean space = false;
        for (int i = 0; i < value.length() && sb.length() < max; i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) {
                space = !sb.isEmpty();
            } else {
                if (space) {
                    sb.append(' ');
                    space = false;
                }
                sb.append(c);
            }
        }
        if (sb.length() >= max && value.length() > max) {
            if (Character.isHighSurrogate(sb.charAt(sb.length() - 1))) {
                sb.setLength(sb.length() - 1);
            }
            sb.append('…');
        }
        return sb.toString();
    }

    private static boolean looksLikeHeader(SortedMap<Integer, String> cells) {
        if (cells.size() < 2) {
            return false;
        }
        for (String value : cells.values()) {
            String v = value.strip();
            if (v.length() > MAX_HEADER_NAME || v.codePoints().noneMatch(Character::isLetter)) {
                return false;
            }
        }
        return true;
    }

    private static String escape(String value) {
        return value.replace("|", "\\|");
    }

    private static int width(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String pad(String value, int width) {
        int missing = width - width(value);
        return missing <= 0 ? value : value + " ".repeat(missing);
    }
}
