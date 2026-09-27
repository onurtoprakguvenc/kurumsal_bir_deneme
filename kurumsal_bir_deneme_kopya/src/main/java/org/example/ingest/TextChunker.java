package org.example.ingest;

import org.example.core.HeapGuard;
import org.example.model.TextChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming normalizer + chunker. Extractors push text fragments as they decode them, so no extractor ever
 * materializes the whole document as one string; only the current chunk buffer and finished chunks live on
 * the heap.
 *
 * <p>Normalization: CR/LF and other line separators become {@code \n}; runs of horizontal whitespace collapse
 * to one space; at most one blank line is kept; control, zero-width and soft-hyphen characters are dropped.
 * Chunks are cut near {@code targetChars} at the best available boundary (blank line, line end, sentence end,
 * space). Concatenating all chunks reproduces the normalized text exactly, so chunk offsets are document
 * offsets.</p>
 */
final class TextChunker {

    static final long DEFAULT_MAX_TOTAL_CHARS = 40_000_000L;

    /** Heap pressure is re-checked every this many characters. */
    private static final long HEAP_CHECK_INTERVAL = 1_000_000;

    private final int target;
    private final int hardMax;
    private final long maxTotalChars;
    private final HeapGuard heapGuard;
    private long nextHeapCheck = HEAP_CHECK_INTERVAL;
    private boolean memoryTruncated;

    private final StringBuilder current;
    private final List<TextChunk> chunks = new ArrayList<>();
    private long chunkStart;
    private long total;
    private int page = -1;
    private int chunkPage = -1;
    private boolean pendingSpace;
    private int newlineRun = 2;
    private boolean lastWasCr;
    private boolean truncated;
    private boolean limitNoted;

    TextChunker(int targetChars, long maxTotalChars) {
        this(targetChars, maxTotalChars, null);
    }

    TextChunker(int targetChars, long maxTotalChars, HeapGuard heapGuard) {
        this.heapGuard = heapGuard;
        if (targetChars < 200) {
            throw new IllegalArgumentException("targetChars must be at least 200");
        }
        this.target = targetChars;
        this.hardMax = targetChars + targetChars / 2;
        this.maxTotalChars = maxTotalChars;
        this.current = new StringBuilder(hardMax + 16);
    }

    /**
     * Marks the beginning of a page (1-based). Chunks never span pages, so every chunk carries an exact page
     * number for citations.
     */
    void startPage(int pageNumber) {
        pendingSpace = false;
        if (total > 0) {
            while (newlineRun < 2) {
                put('\n');
                newlineRun++;
            }
            emit(current.length());
        }
        page = pageNumber;
        chunkPage = pageNumber;
    }

    void append(CharSequence text) {
        for (int i = 0, n = text.length(); i < n && !truncated; i++) {
            accept(text.charAt(i));
        }
    }

    void append(char c) {
        if (!truncated) {
            accept(c);
        }
    }

    /**
     * Appends pre-rendered text (Markdown table blocks) without whitespace collapsing, so column padding
     * survives. Single line breaks are kept as-is; control and invisible characters are still dropped.
     */
    void appendPreformatted(CharSequence text) {
        pendingSpace = false;
        for (int i = 0, n = text.length(); i < n && !truncated; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                put('\n');
                newlineRun = Math.min(2, newlineRun + 1);
            } else if (c == ' ') {
                put(' ');
            } else if (!Character.isISOControl(c) && !isInvisible(c)) {
                put(c);
                newlineRun = 0;
            }
        }
        lastWasCr = false;
    }

    /** Closes the current chunk so the next text starts a new one (used after each table block). */
    void endChunk() {
        emit(current.length());
    }

    /** Discards everything (used when a decoder must restart with another charset). */
    void reset() {
        current.setLength(0);
        chunks.clear();
        chunkStart = 0;
        total = 0;
        page = -1;
        chunkPage = -1;
        pendingSpace = false;
        newlineRun = 2;
        lastWasCr = false;
        truncated = false;
        limitNoted = false;
        memoryTruncated = false;
        nextHeapCheck = HEAP_CHECK_INTERVAL;
    }

    boolean truncated() {
        return truncated;
    }

    /** True when extraction was stopped by heap pressure rather than by a character limit. */
    boolean memoryTruncated() {
        return memoryTruncated;
    }

    /** Characters that can still be appended before the document character limit is reached. */
    long remainingChars() {
        return Math.max(0, maxTotalChars - total);
    }

    long totalChars() {
        return total;
    }

    List<TextChunk> finish() {
        emit(current.length());
        return List.copyOf(chunks);
    }

    private void accept(char c) {
        if (c == '\n' && lastWasCr) {
            lastWasCr = false;
            return;
        }
        lastWasCr = c == '\r';
        switch (c) {
            case '\r', '\n', '\u000B', '\f', '\u0085', '\u2028', '\u2029' -> {
                pendingSpace = false;
                if (newlineRun < 2) {
                    put('\n');
                    newlineRun++;
                }
            }
            case ' ', '\t', '\u00A0' -> pendingSpace = true;
            default -> {
                if (Character.isISOControl(c) || isInvisible(c)) {
                    return;
                }
                if (Character.getType(c) == Character.SPACE_SEPARATOR) {
                    pendingSpace = true;
                    return;
                }
                if (pendingSpace && newlineRun == 0) {
                    put(' ');
                }
                pendingSpace = false;
                newlineRun = 0;
                put(c);
            }
        }
    }

    /** Soft hyphen, zero-width characters and BOM: invisible, never indexed. */
    private static boolean isInvisible(char c) {
        return c == '\u00AD' || c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u2060'
                || c == '\uFEFF' || c == '\uFFFE';
    }

    private void put(char c) {
        if (current.isEmpty()) {
            chunkPage = page;
        }
        current.append(c);
        total++;
        if (total >= maxTotalChars) {
            truncated = true;
        } else if (heapGuard != null && total >= nextHeapCheck) {
            nextHeapCheck = total + HEAP_CHECK_INTERVAL;
            if (heapGuard.underPressure()) {
                stopForMemory();
            }
        }
        int len = current.length();
        if (len < target) {
            return;
        }
        char prev = len >= 2 ? current.charAt(len - 2) : 0;
        if (c == '\n' && (prev == '\n' || len >= target + target / 8)) {
            emit(len);
        } else if (c == ' ' && len >= target + target / 4 && (prev == '.' || prev == '!' || prev == '?' || prev == ';')) {
            emit(len);
        } else if (len >= hardMax) {
            emit(bestHardCut());
        }
    }

    /** Appends a visible note and stops accepting text; the chunks produced so far stay valid. */
    private void stopForMemory() {
        memoryTruncated = true;
        appendNote("\n> Not: JVM bellek eşiği aşıldı (" + heapGuard.describe() + "); belge bu noktada kesildi.\n");
        truncated = true;
    }

    /**
     * Records, once, that the document character limit cut the text, so a search user can see that the rest of the
     * document is not indexed. The note bypasses the limit it reports.
     */
    void noteCharacterLimit() {
        if (!truncated || limitNoted || memoryTruncated) {
            return;
        }
        limitNoted = true;
        appendNote(String.format(java.util.Locale.ROOT,
                "\n> Not: belge %,d karakterlik metin sınırına ulaştı; kalan kısım indekslenmedi.\n", maxTotalChars));
    }

    private void appendNote(String note) {
        for (char c : note.toCharArray()) {
            if (current.isEmpty()) {
                chunkPage = page;
            }
            current.append(c);
            total++;
        }
    }

    private int bestHardCut() {
        int len = current.length();
        for (int i = len - 1; i >= target; i--) {
            if (current.charAt(i) == ' ') {
                return i + 1;
            }
        }
        int cut = len;
        if (Character.isHighSurrogate(current.charAt(cut - 1))) {
            cut--;
        }
        return cut;
    }

    private void emit(int length) {
        if (length <= 0) {
            return;
        }
        String text = current.substring(0, length);
        if (!text.isBlank()) {
            chunks.add(new TextChunk(chunks.size(), chunkPage, chunkStart, text));
        }
        current.delete(0, length);
        chunkStart += length;
        chunkPage = page;
    }
}
