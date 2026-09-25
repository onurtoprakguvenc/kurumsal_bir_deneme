package org.example.index;

import org.example.core.SearchIndex;
import org.example.model.DocumentRecord;
import org.example.model.Location;
import org.example.model.SearchHit;
import org.example.model.SearchResult;
import org.example.model.TextChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.example.core.SearchIndex.Explanation;
import static org.example.core.SearchIndex.Filter;
import static org.example.core.SearchIndex.Proximity;
import static org.example.core.SearchIndex.TermWeight;

/**
 * RAM-resident positional inverted index with BM25 ranking over document chunks. No LLM, no disk.
 *
 * <p>Layout: {@code term → posting list}, where each posting is a chunk ordinal plus a packed {@code int[]} of
 * {@code (position, start, end)} triples. Packing avoids one object per occurrence while still answering
 * {@link #lookup(String)} with full {@link Location} coordinates on demand. Terms live in a sorted map so
 * prefix queries ({@code kira*}) are a range scan.</p>
 *
 * <p>Query syntax: bare words (OR, BM25-ranked with a coordination bonus), {@code "exact phrases"} (required,
 * position-verified), and {@code prefix*}. Words of 4+ characters also match suffixed forms (Turkish
 * agglutination: {@code kira → kiracı, kiranın}) at reduced weight.</p>
 *
 * <p>Concurrency: many concurrent searches, exclusive writers. Documents are tokenized outside the lock.
 * Removal tombstones chunks; the index compacts itself once tombstones dominate.</p>
 */
public final class InvertedIndex implements SearchIndex {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final double PHRASE_BOOST = 1.5;
    private static final double IMPLICIT_VARIANT_WEIGHT = 0.6;
    private static final int IMPLICIT_MIN_TERM = 4;
    private static final int IMPLICIT_MAX_EXTRA_CHARS = 6;
    private static final int MAX_VARIANTS = 64;
    private static final int MAX_MATCHES_PER_HIT = 64;
    private static final int MAX_LOOKUP_LOCATIONS = 50_000;
    private static final int SNIPPET_BEFORE = 70;
    private static final int SNIPPET_AFTER = 190;
    private static final int CLUSTER_WINDOW = 160;
    private static final int COMPACT_MIN_DEAD = 2_000;

    private final Tokenizer tokenizer;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final TreeMap<String, Postings> terms = new TreeMap<>();
    private final ArrayList<Chunk> chunks = new ArrayList<>();
    private final LinkedHashMap<String, Doc> docs = new LinkedHashMap<>();
    private long liveTokens;
    private int liveChunks;
    private int deadChunks;
    private long postingCount;

    public InvertedIndex() {
        this(new Tokenizer());
    }

    public InvertedIndex(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    // ================================================================== storage

    private static final class Chunk {
        final String docId;
        final String fileName;
        final int chunkIndex;
        final int page;
        final long start;
        /** Released (set to "") when the chunk is tombstoned, so removed text does not wait for a compaction. */
        String text;
        final int tokens;
        /** Distinct terms of this chunk, i.e. the postings it owns (keeps {@code postingCount} exact on removal). */
        final int termCount;
        boolean alive = true;

        Chunk(String docId, String fileName, TextChunk chunk, int tokens, int termCount) {
            this.docId = docId;
            this.fileName = fileName;
            this.chunkIndex = chunk.index();
            this.page = chunk.page();
            this.start = chunk.startOffset();
            this.text = chunk.text();
            this.tokens = tokens;
            this.termCount = termCount;
        }
    }

    private record Doc(DocumentRecord record, int firstOrdinal, int count) {
    }

    /** Growable posting list: parallel arrays + one packed triple array. */
    private static final class Postings {
        int size;
        int[] ordinals = new int[2];
        int[] dataStart = new int[2];
        int[] freq = new int[2];
        int[] data = new int[6];
        int dataSize;

        void add(int ordinal, int[] triples, int length) {
            if (size == ordinals.length) {
                int cap = size * 2;
                ordinals = Arrays.copyOf(ordinals, cap);
                dataStart = Arrays.copyOf(dataStart, cap);
                freq = Arrays.copyOf(freq, cap);
            }
            if (dataSize + length > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, dataSize + length));
            }
            ordinals[size] = ordinal;
            dataStart[size] = dataSize;
            freq[size] = length / 3;
            System.arraycopy(triples, 0, data, dataSize, length);
            dataSize += length;
            size++;
        }

        /** Index of the posting for {@code ordinal}, or -1 (ordinals are ascending). */
        int find(int ordinal) {
            int i = Arrays.binarySearch(ordinals, 0, size, ordinal);
            return i >= 0 ? i : -1;
        }
    }

    /** Per-chunk tokenization result, produced without holding the lock. */
    private record PreparedChunk(TextChunk chunk, Map<String, IntList> terms, int tokens) {
    }

    private static final class IntList {
        int[] values = new int[6];
        int size;

        void add3(int a, int b, int c) {
            if (size + 3 > values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = a;
            values[size++] = b;
            values[size++] = c;
        }
    }

    // ================================================================== writes

    @Override
    public boolean add(DocumentRecord document) {
        List<PreparedChunk> prepared = prepare(document);
        lock.writeLock().lock();
        try {
            if (docs.containsKey(document.sha256())) {
                return false;
            }
            append(document, prepared);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private List<PreparedChunk> prepare(DocumentRecord document) {
        List<PreparedChunk> prepared = new ArrayList<>(document.chunks().size());
        for (TextChunk chunk : document.chunks()) {
            Map<String, IntList> local = new HashMap<>();
            int tokens = tokenizer.tokenize(chunk.text(),
                    (term, position, start, end) -> local.computeIfAbsent(term, t -> new IntList())
                            .add3(position, start, end));
            prepared.add(new PreparedChunk(chunk, local, tokens));
        }
        return prepared;
    }

    private void append(DocumentRecord document, List<PreparedChunk> prepared) {
        int first = chunks.size();
        for (PreparedChunk p : prepared) {
            int ordinal = chunks.size();
            chunks.add(new Chunk(document.sha256(), document.fileName(), p.chunk(), p.tokens(), p.terms().size()));
            for (Map.Entry<String, IntList> e : p.terms().entrySet()) {
                terms.computeIfAbsent(e.getKey(), t -> new Postings()).add(ordinal, e.getValue().values, e.getValue().size);
                postingCount++;
            }
            liveTokens += p.tokens();
            liveChunks++;
        }
        docs.put(document.sha256(), new Doc(document, first, prepared.size()));
    }

    @Override
    public boolean remove(String sha256) {
        lock.writeLock().lock();
        try {
            Doc doc = docs.remove(sha256);
            if (doc == null) {
                return false;
            }
            for (int i = doc.firstOrdinal(); i < doc.firstOrdinal() + doc.count(); i++) {
                Chunk chunk = chunks.get(i);
                chunk.alive = false;
                chunk.text = "";
                postingCount -= chunk.termCount;
                liveTokens -= chunk.tokens;
                liveChunks--;
                deadChunks++;
            }
            if (deadChunks >= COMPACT_MIN_DEAD && deadChunks > liveChunks) {
                compact();
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Rebuilds all structures from live documents; caller holds the write lock. */
    private void compact() {
        List<DocumentRecord> alive = docs.values().stream().map(Doc::record).toList();
        terms.clear();
        chunks.clear();
        docs.clear();
        liveTokens = 0;
        liveChunks = 0;
        deadChunks = 0;
        postingCount = 0;
        for (DocumentRecord record : alive) {
            append(record, prepare(record));
        }
        chunks.trimToSize();
    }

    // ================================================================== reads

    @Override
    public boolean contains(String sha256) {
        lock.readLock().lock();
        try {
            return docs.containsKey(sha256);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<DocumentRecord> document(String sha256) {
        lock.readLock().lock();
        try {
            Doc doc = docs.get(sha256);
            return doc == null ? Optional.empty() : Optional.of(doc.record());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<DocumentRecord> documents() {
        lock.readLock().lock();
        try {
            return docs.values().stream().map(Doc::record).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<String> chunkText(String sha256, int chunkIndex) {
        lock.readLock().lock();
        try {
            Doc doc = docs.get(sha256);
            if (doc == null || chunkIndex < 0 || chunkIndex >= doc.count()) {
                return Optional.empty();
            }
            return Optional.of(chunks.get(doc.firstOrdinal() + chunkIndex).text);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Stats stats() {
        lock.readLock().lock();
        try {
            return new Stats(docs.size(), liveChunks, terms.size(), postingCount, liveTokens);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Location> lookup(String term) {
        String folded = term == null ? null : tokenizer.normalizeTerm(term);
        if (folded == null) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            Postings p = terms.get(folded);
            if (p == null) {
                return List.of();
            }
            List<Location> out = new ArrayList<>();
            for (int i = 0; i < p.size && out.size() < MAX_LOOKUP_LOCATIONS; i++) {
                Chunk chunk = chunks.get(p.ordinals[i]);
                if (chunk.alive) {
                    addLocations(p, i, chunk, out, MAX_LOOKUP_LOCATIONS);
                }
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    private static void addLocations(Postings p, int i, Chunk chunk, List<Location> out, int cap) {
        int from = p.dataStart[i];
        int to = from + p.freq[i] * 3;
        for (int k = from; k < to && out.size() < cap; k += 3) {
            int start = p.data[k + 1];
            out.add(new Location(chunk.docId, chunk.chunkIndex, p.data[k], chunk.start + start, p.data[k + 2] - start));
        }
    }

    // ================================================================== search

    private record QueryTerm(String term, boolean explicitPrefix) {
    }

    private record Phrase(String[] terms, int[] offsets) {
    }

    /** {@code excluded}: {@code -word} / {@code -prefix*} terms whose chunks are left out. */
    private record ParsedQuery(List<QueryTerm> terms, List<Phrase> phrases, List<QueryTerm> excluded) {
    }

    private ParsedQuery parse(String query) {
        LinkedHashMap<String, QueryTerm> unique = new LinkedHashMap<>();
        List<Phrase> phrases = new ArrayList<>();
        List<QueryTerm> excluded = new ArrayList<>();
        StringBuilder loose = new StringBuilder();
        int i = 0;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (c == '"') {
                int close = query.indexOf('"', i + 1);
                String inner = close < 0 ? query.substring(i + 1) : query.substring(i + 1, close);
                List<String> pTerms = new ArrayList<>();
                List<Integer> pPos = new ArrayList<>();
                tokenizer.tokenize(inner, (term, position, s, e) -> {
                    pTerms.add(term);
                    pPos.add(position);
                });
                if (pTerms.size() == 1) {
                    unique.putIfAbsent(pTerms.getFirst(), new QueryTerm(pTerms.getFirst(), false));
                } else if (pTerms.size() > 1) {
                    int[] offsets = new int[pTerms.size()];
                    for (int k = 0; k < offsets.length; k++) {
                        offsets[k] = pPos.get(k) - pPos.getFirst();
                        unique.putIfAbsent(pTerms.get(k), new QueryTerm(pTerms.get(k), false));
                    }
                    phrases.add(new Phrase(pTerms.toArray(String[]::new), offsets));
                }
                i = close < 0 ? query.length() : close + 1;
            } else {
                loose.append(c);
                i++;
            }
        }
        for (String word : loose.toString().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            // "-word" excludes; a minus before a digit ("-5", "-%18") keeps its old meaning of a plain term.
            boolean exclude = word.length() > 1 && word.charAt(0) == '-' && Character.isLetter(word.charAt(1));
            String body = exclude ? word.substring(1) : word;
            boolean prefix = body.endsWith("*");
            String stem = prefix ? body.substring(0, body.length() - 1) : body;
            if (exclude) {
                tokenizer.tokenize(stem, (term, position, s, e) -> excluded.add(new QueryTerm(term, prefix)));
                continue;
            }
            tokenizer.tokenize(stem,
                    (term, position, s, e) -> unique.merge(term, new QueryTerm(term, prefix),
                            (a, b) -> new QueryTerm(term, a.explicitPrefix() || b.explicitPrefix())));
        }
        return new ParsedQuery(List.copyOf(unique.values()), phrases, List.copyOf(excluded));
    }

    @Override
    public SearchResult search(String query, int limit) {
        return search(query, limit, Filter.NONE);
    }

    @Override
    public SearchResult search(String query, int limit, Filter filter) {
        long started = System.nanoTime();
        String raw = query == null ? "" : query;
        Filter f = filter == null ? Filter.NONE : filter;
        int k = Math.max(1, Math.min(limit, 200));
        ParsedQuery parsed = parse(raw);
        List<String> looked = parsed.terms().stream().map(QueryTerm::term).toList();
        if (parsed.terms().isEmpty()) {
            return new SearchResult(raw, looked, List.of(), 0, System.nanoTime() - started);
        }

        lock.readLock().lock();
        try {
            if (liveChunks == 0) {
                return new SearchResult(raw, looked, List.of(), 0, System.nanoTime() - started);
            }
            // Dense accumulators indexed by chunk ordinal: no boxing, no per-candidate objects.
            int size = chunks.size();
            double[] score = new double[size];
            long[] mask = new long[size];
            int[] touched = new int[Math.min(size, 4_096)];
            int touchedCount = 0;
            double n = liveChunks;
            double avgdl = Math.max(1.0, (double) liveTokens / liveChunks);
            List<Postings> scored = new ArrayList<>();

            for (Map.Entry<String, double[]> variant : bestVariants(parsed.terms()).entrySet()) {
                long bit = 1L << Math.min((int) variant.getValue()[1], 63);
                double weight = variant.getValue()[0];
                Postings p = terms.get(variant.getKey());
                int df = liveDocumentFrequency(p);
                if (df == 0) {
                    continue;
                }
                scored.add(p);
                double idfWeight = weight * idf(n, df) * (K1 + 1);
                for (int i = 0; i < p.size; i++) {
                    int ordinal = p.ordinals[i];
                    Chunk chunk = chunks.get(ordinal);
                    if (!chunk.alive) {
                        continue;
                    }
                    if (mask[ordinal] == 0) {
                        if (touchedCount == touched.length) {
                            touched = Arrays.copyOf(touched, Math.min(size, touched.length * 2));
                        }
                        touched[touchedCount++] = ordinal;
                    }
                    double tf = p.freq[i];
                    score[ordinal] += idfWeight * tf / (tf + K1 * (1 - B + B * chunk.tokens / avgdl));
                    mask[ordinal] |= bit;
                }
            }

            List<Postings[]> phraseLists = new ArrayList<>();
            for (Phrase phrase : parsed.phrases()) {
                Postings[] lists = new Postings[phrase.terms().length];
                for (int j = 0; j < lists.length; j++) {
                    lists[j] = terms.get(phrase.terms()[j]);
                }
                phraseLists.add(lists);
            }
            List<Postings> excludedLists = excludedPostings(parsed.excluded(), f.excluded());
            List<Postings[][]> nearLists = new ArrayList<>();
            for (Proximity near : f.near()) {
                nearLists.add(new Postings[][]{proximityPostings(near.first()), proximityPostings(near.second())});
            }
            Map<String, Boolean> admitted = new HashMap<>();
            int matched = 0;
            int termCount = Math.min(parsed.terms().size(), 64);
            int[] heapOrd = new int[k];
            double[] heapScore = new double[k];
            int heapSize = 0;
            for (int c = 0; c < touchedCount; c++) {
                int ordinal = touched[c];
                if (!admits(f, admitted, ordinal, excludedLists, nearLists)) {
                    continue;
                }
                boolean keep = true;
                for (int ph = 0; ph < phraseLists.size(); ph++) {
                    if (!phraseMatches(phraseLists.get(ph), parsed.phrases().get(ph).offsets(), ordinal)) {
                        keep = false;
                        break;
                    }
                }
                if (!keep) {
                    continue;
                }
                matched++;
                double s = score[ordinal] * (0.5 + 0.5 * Long.bitCount(mask[ordinal]) / termCount)
                        * (parsed.phrases().isEmpty() ? 1.0 : PHRASE_BOOST);
                if (heapSize < k) {
                    heapOrd[heapSize] = ordinal;
                    heapScore[heapSize] = s;
                    siftUp(heapOrd, heapScore, heapSize++);
                } else if (s > heapScore[0]) {
                    heapOrd[0] = ordinal;
                    heapScore[0] = s;
                    siftDown(heapOrd, heapScore, heapSize);
                }
            }

            Integer[] order = new Integer[heapSize];
            for (int i = 0; i < heapSize; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (x, y) -> heapScore[x] != heapScore[y]
                    ? Double.compare(heapScore[y], heapScore[x])
                    : Integer.compare(heapOrd[x], heapOrd[y]));
            List<SearchHit> hits = new ArrayList<>(heapSize);
            for (Integer i : order) {
                hits.add(toHit(heapOrd[i], heapScore[i], scored));
            }
            return new SearchResult(raw, looked, hits, matched, System.nanoTime() - started);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * A dictionary term can be reached from several query terms ("kira kiracı": "kiraci" is an implicit variant of
     * "kira" and an exact term of its own). Each posting list is scored once, with its best weight, and credited to
     * the query term that gave that weight: {@code term → [weight, queryTermIndex]}.
     */
    private LinkedHashMap<String, double[]> bestVariants(List<QueryTerm> queryTerms) {
        LinkedHashMap<String, double[]> best = new LinkedHashMap<>();
        for (int t = 0; t < queryTerms.size(); t++) {
            for (Map.Entry<String, Double> variant : variants(queryTerms.get(t)).entrySet()) {
                double[] current = best.get(variant.getKey());
                if (current == null || variant.getValue() > current[0]) {
                    best.put(variant.getKey(), new double[]{variant.getValue(), t});
                }
            }
        }
        return best;
    }

    private int liveDocumentFrequency(Postings p) {
        int df = 0;
        for (int i = 0; i < p.size; i++) {
            if (chunks.get(p.ordinals[i]).alive) {
                df++;
            }
        }
        return df;
    }

    private static double idf(double n, int df) {
        return Math.log(1 + (n - df + 0.5) / (df + 0.5));
    }

    /** Posting lists of every excluded term: exact form, plus all extensions for {@code -prefix*}. */
    private List<Postings> excludedPostings(List<QueryTerm> fromQuery, List<String> fromFilter) {
        List<QueryTerm> all = new ArrayList<>(fromQuery);
        for (String raw : fromFilter) {
            boolean prefix = raw.endsWith("*");
            String folded = tokenizer.normalizeTerm(prefix ? raw.substring(0, raw.length() - 1) : raw);
            if (folded != null) {
                all.add(new QueryTerm(folded, prefix));
            }
        }
        List<Postings> out = new ArrayList<>();
        for (QueryTerm qt : all) {
            Postings exact = terms.get(qt.term());
            if (exact != null) {
                out.add(exact);
            }
            if (qt.explicitPrefix()) {
                for (Map.Entry<String, Postings> e : terms.subMap(qt.term(), qt.term() + Character.MAX_VALUE).entrySet()) {
                    if (!e.getKey().equals(qt.term())) {
                        out.add(e.getValue());
                    }
                }
            }
        }
        return out;
    }

    /** Posting lists a proximity operand matches: the term and its suffixed forms, like a normal query term. */
    private Postings[] proximityPostings(String raw) {
        String folded = raw == null ? null : tokenizer.normalizeTerm(raw);
        if (folded == null) {
            return new Postings[0];
        }
        return variants(new QueryTerm(folded, false)).keySet().stream().map(terms::get).toArray(Postings[]::new);
    }

    /** Document scope, exclusions and proximity constraints for one candidate chunk. */
    private boolean admits(Filter f, Map<String, Boolean> admitted, int ordinal, List<Postings> excludedLists,
                           List<Postings[][]> nearLists) {
        Chunk chunk = chunks.get(ordinal);
        if (f.documents() != null && !admitted.computeIfAbsent(chunk.docId, id -> f.documents().test(id))) {
            return false;
        }
        for (Postings p : excludedLists) {
            if (p.find(ordinal) >= 0) {
                return false;
            }
        }
        for (int j = 0; j < nearLists.size(); j++) {
            if (!within(nearLists.get(j)[0], nearLists.get(j)[1], ordinal, f.near().get(j).window())) {
                return false;
            }
        }
        return true;
    }

    /** True when some occurrence of {@code a} and some occurrence of {@code b} are at most {@code window} apart. */
    private static boolean within(Postings[] a, Postings[] b, int ordinal, int window) {
        int[] pa = positions(a, ordinal);
        int[] pb = positions(b, ordinal);
        if (pa.length == 0 || pb.length == 0) {
            return false;
        }
        int i = 0;
        int j = 0;
        while (i < pa.length && j < pb.length) {
            if (pa[i] != pb[j] && Math.abs(pa[i] - pb[j]) <= window) {
                return true;
            }
            if (pa[i] < pb[j]) {
                i++;
            } else {
                j++;
            }
        }
        return false;
    }

    private static int[] positions(Postings[] lists, int ordinal) {
        IntList all = new IntList();
        for (Postings p : lists) {
            int i = p.find(ordinal);
            if (i < 0) {
                continue;
            }
            int from = p.dataStart[i];
            int to = from + p.freq[i] * 3;
            for (int k = from; k < to; k += 3) {
                all.add3(p.data[k], 0, 0);
            }
        }
        int[] out = new int[all.size / 3];
        for (int k = 0; k < out.length; k++) {
            out[k] = all.values[k * 3];
        }
        Arrays.sort(out);
        return out;
    }

    // ================================================================== explanations

    @Override
    public Optional<Explanation> explain(String query, String docId, int chunkIndex) {
        ParsedQuery parsed = parse(query == null ? "" : query);
        if (parsed.terms().isEmpty()) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            Doc doc = docs.get(docId);
            if (doc == null || chunkIndex < 0 || chunkIndex >= doc.count() || liveChunks == 0) {
                return Optional.empty();
            }
            int ordinal = doc.firstOrdinal() + chunkIndex;
            Chunk chunk = chunks.get(ordinal);
            double n = liveChunks;
            double avgdl = Math.max(1.0, (double) liveTokens / liveChunks);
            List<TermWeight> weights = new ArrayList<>();
            long mask = 0;
            double sum = 0;
            for (Map.Entry<String, double[]> variant : bestVariants(parsed.terms()).entrySet()) {
                Postings p = terms.get(variant.getKey());
                int i = p.find(ordinal);
                if (i < 0) {
                    continue;
                }
                int queryTerm = (int) variant.getValue()[1];
                int df = liveDocumentFrequency(p);
                double idf = idf(n, df);
                int tf = p.freq[i];
                double contribution = variant.getValue()[0] * idf * (K1 + 1) * tf
                        / (tf + K1 * (1 - B + B * chunk.tokens / avgdl));
                sum += contribution;
                mask |= 1L << Math.min(queryTerm, 63);
                weights.add(new TermWeight(parsed.terms().get(queryTerm).term(), variant.getKey(),
                        variant.getValue()[0], tf, df, idf, contribution));
            }
            if (weights.isEmpty()) {
                return Optional.empty();
            }
            weights.sort(Comparator.comparingDouble(TermWeight::contribution).reversed());
            int termCount = Math.min(parsed.terms().size(), 64);
            int matchedTerms = Long.bitCount(mask);
            double coordination = 0.5 + 0.5 * matchedTerms / termCount;
            double phraseBoost = parsed.phrases().isEmpty() ? 1.0 : PHRASE_BOOST;
            return Optional.of(new Explanation(docId, chunkIndex, weights, matchedTerms, parsed.terms().size(),
                    coordination, phraseBoost, sum * coordination * phraseBoost, chunk.tokens, avgdl));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Characters of a document read to find its characteristic terms; enough to describe any document. */
    private static final int CHARACTERISTIC_SAMPLE_CHARS = 200_000;
    /** Most frequent candidate terms whose document frequency is looked up. */
    private static final int CHARACTERISTIC_CANDIDATES = 400;

    @Override
    public List<String> characteristicTerms(String docId, int max) {
        lock.readLock().lock();
        try {
            Doc doc = docs.get(docId);
            if (doc == null || max <= 0) {
                return List.of();
            }
            Map<String, int[]> frequency = new HashMap<>();
            int read = 0;
            for (int o = doc.firstOrdinal(); o < doc.firstOrdinal() + doc.count() && read < CHARACTERISTIC_SAMPLE_CHARS; o++) {
                String text = chunks.get(o).text;
                read += text.length();
                tokenizer.tokenize(text, (term, position, s, e) -> {
                    if (term.length() >= 3 && !Character.isDigit(term.charAt(0))) {
                        frequency.computeIfAbsent(term, t -> new int[1])[0]++;
                    }
                });
            }
            List<Map.Entry<String, int[]>> candidates = new ArrayList<>(frequency.entrySet());
            candidates.sort((x, y) -> Integer.compare(y.getValue()[0], x.getValue()[0]));
            if (candidates.size() > CHARACTERISTIC_CANDIDATES) {
                candidates = candidates.subList(0, CHARACTERISTIC_CANDIDATES);
            }
            double n = Math.max(1, liveChunks);
            int first = doc.firstOrdinal();
            int last = first + doc.count();
            List<Map.Entry<String, Double>> scoredTerms = new ArrayList<>();
            for (Map.Entry<String, int[]> e : candidates) {
                Postings p = terms.get(e.getKey());
                if (p == null) {
                    continue;
                }
                int df = 0;
                int outside = 0;
                for (int i = 0; i < p.size; i++) {
                    int ordinal = p.ordinals[i];
                    if (chunks.get(ordinal).alive) {
                        df++;
                        if (ordinal < first || ordinal >= last) {
                            outside++;
                        }
                    }
                }
                if (outside == 0) {
                    continue; // a term no other document has cannot find similar documents
                }
                scoredTerms.add(Map.entry(e.getKey(), e.getValue()[0] * idf(n, df)));
            }
            scoredTerms.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
            return scoredTerms.stream().limit(max).map(Map.Entry::getKey).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    private static void siftUp(int[] ord, double[] score, int i) {
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (score[i] >= score[parent]) {
                return;
            }
            swap(ord, score, i, parent);
            i = parent;
        }
    }

    private static void siftDown(int[] ord, double[] score, int size) {
        int i = 0;
        while (true) {
            int left = 2 * i + 1;
            int right = left + 1;
            int smallest = i;
            if (left < size && score[left] < score[smallest]) {
                smallest = left;
            }
            if (right < size && score[right] < score[smallest]) {
                smallest = right;
            }
            if (smallest == i) {
                return;
            }
            swap(ord, score, i, smallest);
            i = smallest;
        }
    }

    private static void swap(int[] ord, double[] score, int a, int b) {
        int o = ord[a];
        ord[a] = ord[b];
        ord[b] = o;
        double s = score[a];
        score[a] = score[b];
        score[b] = s;
    }

    /** Exact term plus, when applicable, prefix variants from the sorted term dictionary. */
    private Map<String, Double> variants(QueryTerm qt) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (terms.containsKey(qt.term())) {
            out.put(qt.term(), 1.0);
        }
        boolean implicit = qt.term().length() >= IMPLICIT_MIN_TERM && !Character.isDigit(qt.term().charAt(0));
        if (!qt.explicitPrefix() && !implicit) {
            return out;
        }
        SortedMap<String, Postings> tail = terms.subMap(qt.term(), qt.term() + Character.MAX_VALUE);
        for (String candidate : tail.keySet()) {
            if (out.size() >= MAX_VARIANTS) {
                break;
            }
            if (candidate.equals(qt.term())) {
                continue;
            }
            if (qt.explicitPrefix()) {
                out.put(candidate, 1.0);
            } else if (candidate.length() - qt.term().length() <= IMPLICIT_MAX_EXTRA_CHARS) {
                out.put(candidate, IMPLICIT_VARIANT_WEIGHT);
            }
        }
        return out;
    }

    private static boolean phraseMatches(Postings[] lists, int[] offsets, int ordinal) {
        int[] idx = new int[lists.length];
        for (int j = 0; j < lists.length; j++) {
            if (lists[j] == null || (idx[j] = lists[j].find(ordinal)) < 0) {
                return false;
            }
        }
        Postings first = lists[0];
        int from = first.dataStart[idx[0]];
        int to = from + first.freq[idx[0]] * 3;
        for (int a = from; a < to; a += 3) {
            int anchor = first.data[a];
            boolean all = true;
            for (int j = 1; j < lists.length && all; j++) {
                all = hasPosition(lists[j], idx[j], anchor + offsets[j]);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPosition(Postings p, int i, int position) {
        int from = p.dataStart[i];
        int to = from + p.freq[i] * 3;
        for (int k = from; k < to; k += 3) {
            if (p.data[k] == position) {
                return true;
            }
            if (p.data[k] > position) {
                return false;
            }
        }
        return false;
    }

    private SearchHit toHit(int ordinal, double score, List<Postings> scored) {
        Chunk chunk = chunks.get(ordinal);
        record Match(Location location, int termId) {
        }
        List<Match> tagged = new ArrayList<>();
        List<Location> scratch = new ArrayList<>();
        for (int termId = 0; termId < scored.size(); termId++) {
            Postings p = scored.get(termId);
            int i = p.find(ordinal);
            if (i < 0) {
                continue;
            }
            scratch.clear();
            addLocations(p, i, chunk, scratch, MAX_MATCHES_PER_HIT);
            for (Location location : scratch) {
                tagged.add(new Match(location, termId));
            }
        }
        tagged.sort(Comparator.comparingLong(m -> m.location().offset()));
        if (tagged.size() > MAX_MATCHES_PER_HIT) {
            tagged = tagged.subList(0, MAX_MATCHES_PER_HIT);
        }

        // Anchor the snippet on the window that covers the most distinct query terms.
        String text = chunk.text;
        int anchor = 0;
        int bestDistinct = -1;
        for (int i = 0; i < tagged.size(); i++) {
            long windowEnd = tagged.get(i).location().offset() + CLUSTER_WINDOW;
            long seen = 0;
            for (int j = i; j < tagged.size() && tagged.get(j).location().offset() < windowEnd; j++) {
                seen |= 1L << (tagged.get(j).termId() & 63);
            }
            if (Long.bitCount(seen) > bestDistinct) {
                bestDistinct = Long.bitCount(seen);
                anchor = (int) (tagged.get(i).location().offset() - chunk.start);
            }
        }
        List<Location> matches = tagged.stream().map(Match::location).toList();
        int from = Math.max(0, anchor - SNIPPET_BEFORE);
        int to = Math.min(text.length(), anchor + SNIPPET_AFTER);
        if (from > 0) {
            int space = text.indexOf(' ', from);
            if (space >= 0 && space < anchor) {
                from = space + 1;
            }
        }
        if (to < text.length()) {
            int space = text.lastIndexOf(' ', to);
            if (space > anchor) {
                to = space;
            }
        }
        String snippet = text.substring(from, to).replace('\n', ' ').replace('\t', ' ');
        return new SearchHit(chunk.docId, chunk.fileName, chunk.chunkIndex, chunk.page, score, snippet,
                chunk.start + from, from > 0, to < text.length(), matches);
    }
}
