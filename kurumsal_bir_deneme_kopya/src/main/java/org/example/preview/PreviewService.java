package org.example.preview;

import org.example.core.IngestionException;
import org.example.ingest.PreviewExtractor;
import org.example.model.DocumentType;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Memory-bounded inline document preview.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li><b>Preflight</b> — one {@code stat}: the raw size is compared with the per-format limit <em>before</em> a
 *       single byte is parsed. Oversized files get {@link PreviewResult.TooLarge} with the exact size. The limit is
 *       checked against the extension first and again against the magic-byte type, so a file must satisfy both
 *       (a PDF renamed to {@code .txt} is also held to the PDF limit).</li>
 *   <li><b>Bounded extraction</b> — {@link PreviewExtractor} runs the regular streaming extractors with a character
 *       budget of {@link Limits#maxChars()} (and a PDF page cap), so decoding stops as soon as enough text exists.</li>
 *   <li><b>Structuring</b> — chunks become text lines, table rows and section markers in a fixed-size array
 *       ({@link PreviewDocument}).</li>
 * </ol>
 *
 * <h2>Lifecycle</h2>
 * At most one preview is open per service. Opening another, calling {@link #dismiss()} or closing the document
 * releases the previous content immediately; {@link #retainedChars()} returns to 0. Asynchronous requests carry a
 * generation number, so a slow extraction that finishes after the user has moved on is discarded, not shown.
 */
public final class PreviewService implements AutoCloseable {

    /**
     * @param maxBytes     inline preview size limit per format; files above it are never parsed
     * @param maxChars     normalized characters extracted at most
     * @param maxLines     structured lines kept at most
     * @param maxLineChars characters kept per line
     * @param maxPdfPages  PDF pages stripped at most
     */
    public record Limits(Map<DocumentType, Long> maxBytes, long maxChars, int maxLines, int maxLineChars,
                         int maxPdfPages) {
        public Limits {
            EnumMap<DocumentType, Long> copy = new EnumMap<>(DocumentType.class);
            copy.putAll(maxBytes);
            for (DocumentType t : DocumentType.values()) {
                if (copy.getOrDefault(t, 0L) <= 0) {
                    throw new IllegalArgumentException("missing or non-positive size limit for " + t);
                }
            }
            maxBytes = Map.copyOf(copy);
            if (maxChars < 1_000 || maxLines < 10 || maxLineChars < 80 || maxPdfPages < 1) {
                throw new IllegalArgumentException("preview limits too small");
            }
        }

        /** 20 MB PDF, 10 MB Office (DOCX/XLSX/PPTX/PPT), 5 MB plain text and CSV; 64 K chars, 1 500 lines, 12 pages. */
        public static Limits defaults() {
            EnumMap<DocumentType, Long> m = new EnumMap<>(DocumentType.class);
            m.put(DocumentType.PDF, 20L << 20);
            m.put(DocumentType.DOCX, 10L << 20);
            m.put(DocumentType.XLSX, 10L << 20);
            m.put(DocumentType.PPTX, 10L << 20);
            m.put(DocumentType.PPT, 10L << 20);
            m.put(DocumentType.DOC, 10L << 20);
            m.put(DocumentType.XLS, 10L << 20);
            m.put(DocumentType.CSV, 5L << 20);
            m.put(DocumentType.TXT, 5L << 20);
            return new Limits(m, 64_000, 1_500, 1_000, 12);
        }

        public long limitFor(DocumentType type) {
            return maxBytes.get(type);
        }
    }

    private final Limits limits;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong retained = new AtomicLong();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("dwb-preview-", 0).factory());
    private final Object activeLock = new Object();
    private PreviewDocument active;
    private volatile Consumer<PreviewResult> observer = r -> { };

    public PreviewService() {
        this(Limits.defaults());
    }

    public PreviewService(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    public Limits limits() {
        return limits;
    }

    /** Receives every completed result (for audit logging); runs on the thread that produced it. */
    public void setObserver(Consumer<PreviewResult> observer) {
        this.observer = observer == null ? r -> { } : observer;
    }

    // ================================================================== requests

    /** Asynchronous preview on a virtual thread; superseded by any later request. */
    public CompletableFuture<PreviewResult> previewAsync(Path file) {
        long gen = generation.incrementAndGet();
        try {
            return CompletableFuture.supplyAsync(() -> run(file, gen), executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(new PreviewResult.Failed(file, IngestionException.Reason.IO_ERROR,
                    "preview service closed"));
        }
    }

    /** Synchronous preview on the calling thread; replaces (and closes) the currently open preview. */
    public PreviewResult preview(Path file) {
        return run(file, generation.incrementAndGet());
    }

    /**
     * Size gate only: returns the rejection a request for {@code file} would get, or empty when it may be parsed.
     * Costs one {@code stat}; lets a UI grey out the preview button without extracting anything.
     */
    public Optional<PreviewResult> preflight(Path file) {
        if (file == null || file.getFileName() == null) {
            return Optional.of(new PreviewResult.Unsupported(file, "no file"));
        }
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            return Optional.of(new PreviewResult.Failed(file, IngestionException.Reason.NOT_FOUND, "no such file"));
        } catch (AccessDeniedException e) {
            return Optional.of(new PreviewResult.Failed(file, IngestionException.Reason.ACCESS_DENIED,
                    "permission denied"));
        } catch (IOException e) {
            return Optional.of(new PreviewResult.Failed(file, IngestionException.Reason.IO_ERROR, e.getMessage()));
        }
        if (!attrs.isRegularFile()) {
            return Optional.of(new PreviewResult.Unsupported(file, "not a regular file"));
        }
        Optional<DocumentType> byName = DocumentType.fromFileName(file.getFileName().toString());
        if (byName.isEmpty()) {
            return Optional.of(new PreviewResult.Unsupported(file, "no inline preview for this file type"));
        }
        return tooLarge(file, byName.get(), attrs.size());
    }

    private Optional<PreviewResult> tooLarge(Path file, DocumentType type, long size) {
        long limit = limits.limitFor(type);
        return size > limit ? Optional.of(new PreviewResult.TooLarge(file, type, size, limit)) : Optional.empty();
    }

    private PreviewResult run(Path file, long gen) {
        PreviewResult result = produce(file, gen);
        if (result instanceof PreviewResult.Ready ready) {
            synchronized (activeLock) {
                if (gen != generation.get()) {
                    ready.document().close();
                    result = new PreviewResult.Superseded(file);
                } else {
                    replaceActive(ready.document());
                }
            }
        } else if (gen == generation.get()) {
            // A rejection is also the new state of the preview pane: whatever was shown before goes away.
            dismiss();
        }
        observer.accept(result);
        return result;
    }

    private PreviewResult produce(Path file, long gen) {
        Optional<PreviewResult> rejected = preflight(file);
        if (rejected.isPresent()) {
            return rejected.get();
        }
        long started = System.nanoTime();
        try {
            long size = Files.size(file);
            DocumentType detected = PreviewExtractor.detect(file);
            Optional<PreviewResult> byContent = tooLarge(file, detected, size);
            if (byContent.isPresent()) {
                return byContent.get();
            }
            if (gen != generation.get()) {
                return new PreviewResult.Superseded(file);
            }
            PreviewExtractor.Excerpt excerpt = PreviewExtractor.extract(file, detected, limits.maxChars(),
                    limits.maxPdfPages(), null);
            PreviewDocument document = new PreviewDocument(file, detected, size, excerpt.pages(),
                    System.nanoTime() - started, limits.maxLines(), limits.maxLineChars());
            document.fill(excerpt.chunks(), excerpt.truncated());
            if (document.lineCount() == 0) {
                document.close();
                return new PreviewResult.Failed(file, IngestionException.Reason.EMPTY, "no extractable text");
            }
            return new PreviewResult.Ready(file, document);
        } catch (IngestionException e) {
            return new PreviewResult.Failed(file, e.reason(), e.getMessage());
        } catch (IOException e) {
            return new PreviewResult.Failed(file, IngestionException.Reason.IO_ERROR, e.getMessage());
        } catch (OutOfMemoryError e) {
            return new PreviewResult.Failed(file, IngestionException.Reason.MEMORY_PRESSURE, "out of memory");
        }
    }

    private void replaceActive(PreviewDocument next) {
        PreviewDocument previous = active;
        active = next;
        retained.addAndGet(next.retainedChars());
        long charge = next.retainedChars();
        next.onClose(d -> {
            retained.addAndGet(-charge);
            synchronized (activeLock) {
                if (active == d) {
                    active = null;
                }
            }
        });
        if (previous != null) {
            previous.close();
        }
    }

    // ================================================================== lifecycle

    /** The open preview, if any. */
    public Optional<PreviewDocument> active() {
        synchronized (activeLock) {
            return Optional.ofNullable(active);
        }
    }

    /** Closes the open preview and cancels in-flight requests; memory returns to baseline. */
    public void dismiss() {
        generation.incrementAndGet();
        PreviewDocument current;
        synchronized (activeLock) {
            current = active;
            active = null;
        }
        if (current != null) {
            current.close();
        }
    }

    /** Characters held by open previews (0 after {@link #dismiss()}). */
    public long retainedChars() {
        return retained.get();
    }

    @Override
    public void close() {
        dismiss();
        executor.shutdownNow();
    }
}
