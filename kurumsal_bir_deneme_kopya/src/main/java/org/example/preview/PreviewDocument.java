package org.example.preview;

import org.example.model.DocumentType;
import org.example.model.TextChunk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Bounded, line-structured preview content.
 *
 * <p>Lines live in one fixed-capacity array sized at construction; nothing grows past {@code maxLines} lines or
 * {@code maxLineChars} characters per line, and no whole-document string is ever built — chunks from the extractor
 * are split into lines as they are consumed and then dropped. {@link #close()} nulls the array, so the only
 * reference to the text is gone the moment a preview is dismissed.</p>
 *
 * <p>Access is paged ({@link #lines(int, int)}) so a virtualized list cell can pull just the visible window.</p>
 */
public final class PreviewDocument implements AutoCloseable {

    /** A structured preview line. */
    public sealed interface PreviewLine permits Text, TableRow, Section, Notice {
        int page();
    }

    /** Ordinary paragraph text. */
    public record Text(int page, String text) implements PreviewLine {
    }

    /** One spreadsheet / table row; {@code header} for the row above a Markdown separator. */
    public record TableRow(int page, List<String> cells, boolean header) implements PreviewLine {
        public TableRow {
            cells = List.copyOf(cells);
        }
    }

    /** Page, sheet or slide boundary. */
    public record Section(int page, String label) implements PreviewLine {
    }

    /** Extractor note (limits reached, truncated slide…) or preview cutoff marker. */
    public record Notice(int page, String text) implements PreviewLine {
    }

    private final Path path;
    private final DocumentType type;
    private final long sizeBytes;
    private final int pages;
    private final long extractNanos;
    private final int maxLineChars;
    private final CopyOnWriteArrayList<Consumer<PreviewDocument>> closeListeners = new CopyOnWriteArrayList<>();

    private PreviewLine[] lines;
    private int count;
    private long chars;
    private boolean truncated;
    private volatile boolean closed;

    PreviewDocument(Path path, DocumentType type, long sizeBytes, int pages, long extractNanos, int maxLines,
                    int maxLineChars) {
        this.path = Objects.requireNonNull(path);
        this.type = Objects.requireNonNull(type);
        this.sizeBytes = sizeBytes;
        this.pages = pages;
        this.extractNanos = extractNanos;
        this.maxLineChars = maxLineChars;
        this.lines = new PreviewLine[Math.max(1, maxLines)];
    }

    // ================================================================== building (package-private)

    /**
     * Splits extractor chunks into structured lines. Lines that span a chunk boundary are stitched with a small
     * carry buffer bounded by {@code maxLineChars}.
     */
    void fill(List<TextChunk> chunks, boolean sourceTruncated) {
        StringBuilder carry = new StringBuilder(128);
        int lastPage = Integer.MIN_VALUE;
        int carryPage = -1;
        boolean previousWasTable = false;
        outer:
        for (TextChunk chunk : chunks) {
            if (chunk.page() != lastPage && chunk.page() > 0 && carry.isEmpty() && !startsWithSection(chunk.text())) {
                if (!add(new Section(chunk.page(), sectionLabel(chunk.page())))) {
                    break;
                }
            }
            lastPage = chunk.page();
            String text = chunk.text();
            int start = 0;
            for (int i = 0; i <= text.length(); i++) {
                if (i < text.length() && text.charAt(i) != '\n') {
                    continue;
                }
                if (i == text.length()) {
                    appendBounded(carry, text, start, i);
                    carryPage = chunk.page();
                    break;
                }
                appendBounded(carry, text, start, i);
                start = i + 1;
                PreviewLine line = classify(carry, chunk.page(), previousWasTable);
                carry.setLength(0);
                if (line == null) {
                    continue;
                }
                if (line instanceof TableRow row && row.header() && previousWasTable && count > 0
                        && lines[count - 1] instanceof TableRow prev) {
                    lines[count - 1] = new TableRow(prev.page(), prev.cells(), true);
                    continue;
                }
                previousWasTable = line instanceof TableRow;
                if (!add(line)) {
                    break outer;
                }
            }
        }
        if (!carry.isEmpty() && count < lines.length) {
            PreviewLine tail = classify(carry, carryPage, previousWasTable);
            if (tail != null) {
                add(tail);
            }
        }
        truncated = sourceTruncated || count == lines.length;
        if (truncated) {
            if (count == lines.length) {
                count--;
            }
            lines[count++] = new Notice(-1, "… preview shows the beginning only; open the file for the full document");
        }
    }

    private void appendBounded(StringBuilder carry, String text, int from, int to) {
        int room = maxLineChars - carry.length();
        if (room > 0) {
            carry.append(text, from, Math.min(to, from + room));
        }
    }

    private boolean add(PreviewLine line) {
        if (count >= lines.length) {
            return false;
        }
        lines[count++] = line;
        chars += switch (line) {
            case Text t -> t.text().length();
            case TableRow r -> r.cells().stream().mapToInt(String::length).sum();
            case Section s -> s.label().length();
            case Notice n -> n.text().length();
        };
        return true;
    }

    /** Returns {@code null} for lines that carry nothing (blank lines, Markdown table separators). */
    private static PreviewLine classify(CharSequence raw, int page, boolean previousWasTable) {
        String line = raw.toString().strip();
        if (line.isEmpty()) {
            return null;
        }
        if (line.startsWith("=== ") && line.endsWith(" ===") && line.length() > 8) {
            return new Section(page, line.substring(4, line.length() - 4).strip());
        }
        if (line.startsWith("> ")) {
            return new Notice(page, line.substring(2));
        }
        if (line.length() > 1 && line.charAt(0) == '|' && line.charAt(line.length() - 1) == '|') {
            String inner = line.substring(1, line.length() - 1);
            if (inner.chars().allMatch(c -> c == '-' || c == ':' || c == '|' || c == ' ')) {
                // Separator under a header row: marks the previous row as the header.
                return previousWasTable ? new TableRow(page, List.of(), true) : null;
            }
            List<String> cells = new ArrayList<>();
            for (String cell : inner.split("\\|", -1)) {
                cells.add(cell.strip());
            }
            return new TableRow(page, cells, false);
        }
        return new Text(page, line);
    }

    private static boolean startsWithSection(String text) {
        return text.startsWith("=== ");
    }

    private String sectionLabel(int page) {
        return switch (type) {
            case PDF, DOCX, DOC -> "Page " + page;
            case XLSX, XLS, CSV -> "Sheet " + page;
            case PPTX, PPT -> "Slide " + page;
            case TXT -> "Part " + page;
        };
    }

    // ================================================================== access

    public Path path() {
        return path;
    }

    public DocumentType type() {
        return type;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    /** Pages / sheets / slides of the whole document as reported by the extractor, or {@code -1}. */
    public int pages() {
        return pages;
    }

    public double extractMillis() {
        return extractNanos / 1_000_000.0;
    }

    public synchronized boolean truncated() {
        return truncated;
    }

    public synchronized int lineCount() {
        return count;
    }

    /** Characters currently held (0 once closed). */
    public synchronized long retainedChars() {
        return closed ? 0 : chars;
    }

    public boolean closed() {
        return closed;
    }

    public synchronized PreviewLine line(int index) {
        ensureOpen();
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("line " + index + " of " + count);
        }
        return lines[index];
    }

    /** A window of lines for virtualized rendering. */
    public synchronized List<PreviewLine> lines(int from, int max) {
        ensureOpen();
        int start = Math.max(0, Math.min(from, count));
        int end = Math.min(count, start + Math.max(0, max));
        return List.of(Arrays.copyOfRange(lines, start, end));
    }

    /** Streams every line in order to {@code sink}. */
    public void forEach(Consumer<PreviewLine> sink) {
        int n = lineCount();
        for (int i = 0; i < n; i++) {
            PreviewLine line;
            synchronized (this) {
                if (closed || i >= count) {
                    return;
                }
                line = lines[i];
            }
            sink.accept(line);
        }
    }

    void onClose(Consumer<PreviewDocument> listener) {
        closeListeners.add(listener);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("preview of " + path.getFileName() + " was closed");
        }
    }

    /** Releases the content immediately; idempotent. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            Arrays.fill(lines, 0, count, null);
            lines = new PreviewLine[0];
            count = 0;
        }
        for (Consumer<PreviewDocument> l : closeListeners) {
            l.accept(this);
        }
        closeListeners.clear();
    }
}
