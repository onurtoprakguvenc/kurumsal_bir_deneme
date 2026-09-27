package org.example.state;

import org.example.model.Location;
import org.example.model.SearchHit;
import org.example.model.SearchResult;
import org.example.model.TextChunk;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Presentation model for search results with two audiences.
 *
 * <ul>
 *   <li><b>Simple mode</b> — {@link SimpleView}: clean document name, match snippet and a relevance badge with a
 *       percentage relative to the best hit. Nothing technical.</li>
 *   <li><b>Detailed / IT mode</b> — {@link DetailedView}: raw BM25 score, per-term frequencies, the chunk
 *       coordinate ({@code p.4 #2 @1830}), extraction latency, query latency and live JVM memory.</li>
 * </ul>
 *
 * <h2>Allocation contract</h2>
 * Results live in one reusable table of parallel arrays ({@code SearchHit[]}, {@code double[]}, {@code byte[]}) that
 * grows only when a larger result arrives and is overwritten in place otherwise. Switching mode flips one field and
 * notifies listeners; the table is neither rebuilt nor copied. Views are projected lazily, per row, when a cell or
 * the terminal actually renders it — so only visible rows ever cost an allocation, and
 * {@link #render(int, StringBuilder)} writes straight into a caller-owned buffer.
 *
 * <h2>Threading</h2>
 * Results may be published from any thread (typically a virtual search thread) while the UI reads. Listeners run on
 * the publishing thread; a JavaFX adapter should hop to the FX thread with {@code Platform.runLater} and can mirror
 * {@link #mode()} into an {@code ObjectProperty<ViewMode>} there.
 */
public final class ViewModeCoordinator {

    public enum ViewMode {
        SIMPLE, DETAILED;

        public ViewMode toggled() {
            return this == SIMPLE ? DETAILED : SIMPLE;
        }
    }

    /** Intuitive relevance bucket derived from the percentage relative to the top hit. */
    public enum Relevance {
        HIGH("High"), MEDIUM("Medium"), LOW("Low");

        private final String label;

        Relevance(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Relevance of(int percent) {
            return percent >= 75 ? HIGH : percent >= 40 ? MEDIUM : LOW;
        }
    }

    /** A row projected for one audience. */
    public sealed interface ResultView permits SimpleView, DetailedView {
        int row();

        String documentName();

        String snippet();
    }

    /** User-facing contract: no scores, no offsets, no internals. */
    public record SimpleView(int row, String documentName, String snippet, int relevancePercent,
                             Relevance relevance) implements ResultView {
    }

    /**
     * Engineering contract.
     *
     * @param termFrequencies occurrences per query term inside the chunk, in query order (suffixed forms credited
     *                        to their stem; {@code ?} when the chunk text is unavailable)
     * @param coordinate      compact location, e.g. {@code p.4 #2 @1830} (page, chunk ordinal, document offset)
     * @param parseMillis     extraction latency of the document at ingestion, or {@code -1} if unknown
     * @param queryMillis     latency of the query that produced this row
     */
    public record DetailedView(int row, String documentName, String snippet, String docId, double bm25,
                               int relevancePercent, Map<String, Integer> termFrequencies, int matchCount,
                               String coordinate, int page, int chunkIndex, long offset, long parseMillis,
                               double queryMillis, int matchedChunks, MemorySnapshot memory) implements ResultView {
        public DetailedView {
            termFrequencies = Collections.unmodifiableMap(new LinkedHashMap<>(termFrequencies));
        }
    }

    /** Point-in-time JVM memory and GC figures. */
    public record MemorySnapshot(long usedBytes, long committedBytes, long maxBytes, long gcCount, long gcMillis) {

        public static MemorySnapshot now() {
            Runtime rt = Runtime.getRuntime();
            long count = 0;
            long millis = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                count += Math.max(0, gc.getCollectionCount());
                millis += Math.max(0, gc.getCollectionTime());
            }
            return new MemorySnapshot(rt.totalMemory() - rt.freeMemory(), rt.totalMemory(), rt.maxMemory(), count,
                    millis);
        }

        public int usedPercent() {
            return maxBytes <= 0 || maxBytes == Long.MAX_VALUE ? 0 : (int) Math.round(100.0 * usedBytes / maxBytes);
        }

        public String describe() {
            return String.format(Locale.ROOT, "heap %d/%d MB (%d%%, committed %d MB), gc %d runs, %d ms",
                    usedBytes >> 20, maxBytes >> 20, usedPercent(), committedBytes >> 20, gcCount, gcMillis);
        }
    }

    /** Read-only access to engine facts the detailed view needs; decouples this class from the index. */
    public interface TelemetrySource {
        Optional<TextChunk> chunk(String docId, int chunkIndex);

        /** Folds a surface form like the index does; may return {@code null} when nothing remains. */
        String normalize(String surface);

        long parseMillis(String docId);

        TelemetrySource NONE = new TelemetrySource() {
            @Override
            public Optional<TextChunk> chunk(String docId, int chunkIndex) {
                return Optional.empty();
            }

            @Override
            public String normalize(String surface) {
                return surface.toLowerCase(Locale.ROOT);
            }

            @Override
            public long parseMillis(String docId) {
                return -1;
            }
        };
    }

    @FunctionalInterface
    public interface ModeListener {
        void modeChanged(ViewMode previous, ViewMode current);
    }

    @FunctionalInterface
    public interface ResultsListener {
        /** @param generation monotonically increasing publish counter, for discarding stale UI updates */
        void resultsChanged(long generation, int size);
    }

    private static final int INITIAL_CAPACITY = 16;

    private final TelemetrySource telemetry;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final CopyOnWriteArrayList<ModeListener> modeListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ResultsListener> resultListeners = new CopyOnWriteArrayList<>();

    private final Object modeLock = new Object();
    private volatile ViewMode mode;

    // Reusable result table (guarded by lock).
    private SearchHit[] hits = new SearchHit[INITIAL_CAPACITY];
    private double[] scores = new double[INITIAL_CAPACITY];
    private byte[] percents = new byte[INITIAL_CAPACITY];
    private int size;
    private String query = "";
    private List<String> terms = List.of();
    private double queryMillis;
    private int matchedChunks;
    private long generation;

    public ViewModeCoordinator(TelemetrySource telemetry, ViewMode initial) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
        this.mode = Objects.requireNonNull(initial, "initial must not be null");
    }

    public ViewModeCoordinator() {
        this(TelemetrySource.NONE, ViewMode.SIMPLE);
    }

    // ================================================================== mode

    public ViewMode mode() {
        return mode;
    }

    /** Sets the mode; a no-op (and no notification) when unchanged. */
    public void setMode(ViewMode next) {
        Objects.requireNonNull(next, "mode must not be null");
        ViewMode previous;
        synchronized (modeLock) {
            previous = mode;
            if (previous == next) {
                return;
            }
            mode = next;
        }
        notifyMode(previous, next);
    }

    /** Atomically flips the mode and returns the new one. */
    public ViewMode toggle() {
        ViewMode previous;
        ViewMode next;
        synchronized (modeLock) {
            previous = mode;
            next = previous.toggled();
            mode = next;
        }
        notifyMode(previous, next);
        return next;
    }

    private void notifyMode(ViewMode previous, ViewMode next) {
        for (ModeListener l : modeListeners) {
            l.modeChanged(previous, next);
        }
    }

    public Subscription onModeChanged(ModeListener listener) {
        modeListeners.add(Objects.requireNonNull(listener));
        return () -> modeListeners.remove(listener);
    }

    public Subscription onResultsChanged(ResultsListener listener) {
        resultListeners.add(Objects.requireNonNull(listener));
        return () -> resultListeners.remove(listener);
    }

    // ================================================================== results table

    /** Loads {@code result} into the reusable table, overwriting the previous rows in place. */
    public void publish(SearchResult result) {
        Objects.requireNonNull(result, "result must not be null");
        List<SearchHit> incoming = result.hits();
        long gen;
        int n = incoming.size();
        lock.writeLock().lock();
        try {
            ensureCapacity(n);
            double top = n == 0 ? 0 : incoming.getFirst().score();
            for (int i = 0; i < n; i++) {
                SearchHit hit = incoming.get(i);
                hits[i] = hit;
                scores[i] = hit.score();
                top = Math.max(top, hit.score());
            }
            for (int i = 0; i < n; i++) {
                percents[i] = (byte) (top <= 0 ? 0 : Math.max(1, Math.min(100, Math.round(100 * scores[i] / top))));
            }
            // Release references to rows from a longer previous result so they can be collected.
            Arrays.fill(hits, n, size > n ? size : n, null);
            size = n;
            query = result.query();
            terms = result.terms();
            queryMillis = result.elapsedMillis();
            matchedChunks = result.matchedChunks();
            gen = ++generation;
        } finally {
            lock.writeLock().unlock();
        }
        for (ResultsListener l : resultListeners) {
            l.resultsChanged(gen, n);
        }
    }

    /** Empties the table without shrinking it. */
    public void clear() {
        publish(new SearchResult("", List.of(), List.of(), 0, 0));
    }

    private void ensureCapacity(int n) {
        if (n <= hits.length) {
            return;
        }
        int cap = Math.max(n, hits.length * 2);
        hits = Arrays.copyOf(hits, cap);
        scores = Arrays.copyOf(scores, cap);
        percents = Arrays.copyOf(percents, cap);
    }

    public int size() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }

    public long generation() {
        lock.readLock().lock();
        try {
            return generation;
        } finally {
            lock.readLock().unlock();
        }
    }

    public String query() {
        lock.readLock().lock();
        try {
            return query;
        } finally {
            lock.readLock().unlock();
        }
    }

    public double queryMillis() {
        lock.readLock().lock();
        try {
            return queryMillis;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int matchedChunks() {
        lock.readLock().lock();
        try {
            return matchedChunks;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Raw hit behind a row (for actions such as open/reveal), or empty when out of range. */
    public Optional<SearchHit> hit(int row) {
        lock.readLock().lock();
        try {
            return row >= 0 && row < size ? Optional.of(hits[row]) : Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Identity of the backing array; lets tests assert that toggling never reallocates the table. */
    public int tableIdentity() {
        lock.readLock().lock();
        try {
            return System.identityHashCode(hits);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ================================================================== projections

    /** Projects {@code row} for the current mode. */
    public ResultView view(int row) {
        return mode == ViewMode.SIMPLE ? simple(row) : detailed(row);
    }

    public SimpleView simple(int row) {
        lock.readLock().lock();
        try {
            checkRow(row);
            int percent = percents[row];
            return new SimpleView(row, cleanName(hits[row].fileName()), hits[row].snippet(), percent,
                    Relevance.of(percent));
        } finally {
            lock.readLock().unlock();
        }
    }

    public DetailedView detailed(int row) {
        SearchHit hit;
        int percent;
        double qMillis;
        int matched;
        List<String> queryTerms;
        lock.readLock().lock();
        try {
            checkRow(row);
            hit = hits[row];
            percent = percents[row];
            qMillis = queryMillis;
            matched = matchedChunks;
            queryTerms = terms;
        } finally {
            lock.readLock().unlock();
        }
        // Chunk lookups and normalization happen outside the lock; the hit itself is immutable.
        return new DetailedView(row, hit.fileName(), hit.snippet(), hit.docId(), hit.score(), percent,
                termFrequencies(hit, queryTerms), hit.matches().size(), coordinate(hit), hit.page(), hit.chunkIndex(),
                hit.firstMatchOffset(), telemetry.parseMillis(hit.docId()), qMillis, matched, MemorySnapshot.now());
    }

    /**
     * Appends {@code row} as terminal text for the current mode, without intermediate view objects in simple mode.
     */
    public void render(int row, StringBuilder out) {
        if (mode == ViewMode.DETAILED) {
            DetailedView d = detailed(row);
            out.append(String.format(Locale.ROOT, "%2d. %s  bm25=%.3f rel=%d%% tf=%s at %s parse=%s",
                    row + 1, d.documentName(), d.bm25(), d.relevancePercent(), d.termFrequencies(), d.coordinate(),
                    d.parseMillis() < 0 ? "n/a" : d.parseMillis() + "ms"));
            out.append("\n    ").append(d.snippet());
            return;
        }
        lock.readLock().lock();
        try {
            checkRow(row);
            int percent = percents[row];
            out.append(row + 1 < 10 ? " " : "").append(row + 1).append(". ").append(cleanName(hits[row].fileName()))
                    .append("  [").append(Relevance.of(percent).label()).append(' ').append(percent).append("%]")
                    .append("\n    ").append(hits[row].snippet());
        } finally {
            lock.readLock().unlock();
        }
    }

    private void checkRow(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row " + row + " of " + size);
        }
    }

    private Map<String, Integer> termFrequencies(SearchHit hit, List<String> queryTerms) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String term : queryTerms) {
            out.put(term, 0);
        }
        Optional<TextChunk> chunk = telemetry.chunk(hit.docId(), hit.chunkIndex());
        for (Location match : hit.matches()) {
            String key = null;
            if (chunk.isPresent()) {
                TextChunk c = chunk.get();
                int from = (int) (match.offset() - c.startOffset());
                int to = from + match.length();
                if (from >= 0 && to <= c.text().length()) {
                    key = attribute(telemetry.normalize(c.text().substring(from, to)), queryTerms);
                }
            }
            out.merge(key == null ? "?" : key, 1, Integer::sum);
        }
        return out;
    }

    /** Credits a matched surface form to the longest query term it equals or extends (kira → kiracı). */
    private static String attribute(String folded, List<String> queryTerms) {
        if (folded == null) {
            return null;
        }
        String best = null;
        for (String term : queryTerms) {
            if (folded.startsWith(term) && (best == null || term.length() > best.length())) {
                best = term;
            }
        }
        return best == null ? folded : best;
    }

    /** {@code p.4 #2 @1830} for paged formats, {@code #2 @1830} otherwise. */
    public static String coordinate(SearchHit hit) {
        String base = "#" + hit.chunkIndex() + " @" + hit.firstMatchOffset();
        return hit.page() > 0 ? "p." + hit.page() + " " + base : base;
    }

    /** Strips fork/peer tags ({@code "plan.xlsx [beta · …]"} → {@code "plan.xlsx"}) for the simple audience. */
    public static String cleanName(String fileName) {
        int tag = fileName.indexOf(" [");
        return tag > 0 && fileName.endsWith("]") ? fileName.substring(0, tag) : fileName;
    }
}
