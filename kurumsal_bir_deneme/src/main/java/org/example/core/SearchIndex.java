package org.example.core;

import org.example.model.DocumentRecord;
import org.example.model.Location;
import org.example.model.SearchHit;
import org.example.model.SearchResult;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Port: in-memory keyword index over document chunks.
 */
public interface SearchIndex {

    /** Adds a document; returns {@code false} when a document with the same SHA-256 is already indexed. */
    boolean add(DocumentRecord document);

    boolean remove(String sha256);

    boolean contains(String sha256);

    SearchResult search(String query, int limit);

    /** All live occurrences of a single term (normalized like indexed text), in index order. */
    List<Location> lookup(String term);

    Optional<String> chunkText(String sha256, int chunkIndex);

    Optional<DocumentRecord> document(String sha256);

    List<DocumentRecord> documents();

    Stats stats();

    record Stats(int documents, int chunks, int terms, long postings, long tokens) {
    }

    // ------------------------------------------------------------------ scoped search

    /**
     * Restrictions applied <em>before</em> ranking, so the top-k are the best hits that satisfy them.
     *
     * @param documents which documents may appear (by SHA-256); {@code null} admits all
     * @param excluded  terms (normalized like indexed text) whose chunks are left out
     * @param near      pairs of terms that must occur within a number of word positions of each other
     */
    record Filter(Predicate<String> documents, List<String> excluded, List<Proximity> near) {
        public static final Filter NONE = new Filter(null, List.of(), List.of());

        public Filter {
            excluded = excluded == null ? List.of() : List.copyOf(excluded);
            near = near == null ? List.of() : List.copyOf(near);
        }

        public boolean isEmpty() {
            return documents == null && excluded.isEmpty() && near.isEmpty();
        }

        public Filter withDocuments(Predicate<String> more) {
            if (more == null) {
                return this;
            }
            return new Filter(documents == null ? more : documents.and(more), excluded, near);
        }
    }

    /** Both terms within {@code window} word positions (stop words count as positions). */
    record Proximity(String first, String second, int window) {
    }

    /**
     * Search restricted by {@code filter}. The default implementation over-fetches and filters by document only;
     * {@link org.example.index.InvertedIndex} applies every restriction inside the ranking loop.
     */
    default SearchResult search(String query, int limit, Filter filter) {
        if (filter == null || filter.isEmpty()) {
            return search(query, limit);
        }
        SearchResult all = search(query, 200);
        List<SearchHit> kept = all.hits().stream()
                .filter(h -> filter.documents() == null || filter.documents().test(h.docId()))
                .limit(Math.max(1, limit)).toList();
        return new SearchResult(all.query(), all.terms(), kept, kept.size(), all.elapsedNanos());
    }

    // ------------------------------------------------------------------ explanations

    /** One matched dictionary term's share of a chunk's score. */
    record TermWeight(String queryTerm, String indexTerm, double variantWeight, int termFrequency,
                      int documentFrequency, double idf, double contribution) {
    }

    /** How a chunk's BM25 score for a query was put together. */
    record Explanation(String docId, int chunkIndex, List<TermWeight> terms, int matchedQueryTerms, int queryTerms,
                       double coordination, double phraseBoost, double score, int chunkTokens,
                       double averageChunkTokens) {
        public Explanation {
            terms = List.copyOf(terms);
        }
    }

    /** Score breakdown of one chunk for {@code query}; empty when the chunk is unknown or does not match. */
    default Optional<Explanation> explain(String query, String docId, int chunkIndex) {
        return Optional.empty();
    }

    /**
     * Terms that characterize a document relative to the rest of the index (frequent in it, rare elsewhere, and
     * present in at least one other document), best first — the basis of "more like this".
     */
    default List<String> characteristicTerms(String docId, int max) {
        return List.of();
    }
}
