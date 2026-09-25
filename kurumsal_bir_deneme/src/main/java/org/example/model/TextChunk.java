package org.example.model;

import java.util.Objects;

/**
 * A contiguous slice of a document's normalized text.
 *
 * @param index       zero-based chunk number within the document
 * @param page        1-based page on which the chunk starts, or {@code -1} when the format has no pages
 * @param startOffset character offset of the first character within the normalized document text
 * @param text        chunk text; concatenating all chunks in order reproduces the normalized document
 */
public record TextChunk(int index, int page, long startOffset, String text) {

    public TextChunk {
        Objects.requireNonNull(text, "text must not be null");
        if (index < 0 || startOffset < 0) {
            throw new IllegalArgumentException("index and startOffset must not be negative");
        }
    }

    public long endOffset() {
        return startOffset + text.length();
    }
}
