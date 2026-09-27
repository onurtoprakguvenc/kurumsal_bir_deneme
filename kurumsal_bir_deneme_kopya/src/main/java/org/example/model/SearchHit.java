package org.example.model;

import java.util.List;

/**
 * One ranked chunk returned by a keyword search.
 *
 * @param docId          document SHA-256
 * @param fileName       document display name
 * @param chunkIndex     matching chunk
 * @param page           page on which the chunk starts, or {@code -1}
 * @param score          BM25 score (higher is better)
 * @param snippet        verbatim excerpt of the chunk around the densest match cluster (line breaks as spaces)
 * @param snippetOffset  document offset of the first snippet character
 * @param clippedStart   {@code true} when text precedes the snippet in the chunk
 * @param clippedEnd     {@code true} when text follows the snippet in the chunk
 * @param matches        occurrences of query terms inside the chunk, ascending by offset
 */
public record SearchHit(String docId,
                        String fileName,
                        int chunkIndex,
                        int page,
                        double score,
                        String snippet,
                        long snippetOffset,
                        boolean clippedStart,
                        boolean clippedEnd,
                        List<Location> matches) {

    public SearchHit {
        matches = List.copyOf(matches);
    }

    /** Offset of the first match, or the snippet start when the hit has no located match. */
    public long firstMatchOffset() {
        return matches.isEmpty() ? snippetOffset : matches.getFirst().offset();
    }
}
