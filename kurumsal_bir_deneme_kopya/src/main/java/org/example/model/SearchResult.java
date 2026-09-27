package org.example.model;

import java.util.List;

/**
 * Outcome of a keyword search.
 *
 * @param query         raw query
 * @param terms         normalized query terms actually looked up
 * @param hits          ranked hits, best first
 * @param matchedChunks chunks that matched before top-k truncation
 * @param elapsedNanos  wall-clock search time inside the index
 */
public record SearchResult(String query, List<String> terms, List<SearchHit> hits, int matchedChunks,
                           long elapsedNanos) {

    public SearchResult {
        terms = List.copyOf(terms);
        hits = List.copyOf(hits);
    }

    public double elapsedMillis() {
        return elapsedNanos / 1_000_000.0;
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }
}
