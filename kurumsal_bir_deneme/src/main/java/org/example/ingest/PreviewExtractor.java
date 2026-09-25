package org.example.ingest;

import org.example.core.HeapGuard;
import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;
import org.example.model.DocumentType;
import org.example.model.TextChunk;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Public entry point for bounded, preview-sized extraction with the same extractors ingestion uses.
 *
 * <p>The extractors already stream into a {@link TextChunker} and stop decoding once it reports
 * {@code truncated()}, so a preview is simply an extraction whose character budget is a few kilobytes instead of
 * forty million: DOCX/PPTX/XLSX/CSV/TXT readers bail out of their StAX or char loops at the cap, and PDFs are only
 * stripped up to {@code maxPdfPages}. Nothing ever holds the whole document text.</p>
 */
public final class PreviewExtractor {

    /** Chunk size used for previews; small chunks keep the per-chunk string allocation small. */
    private static final int PREVIEW_CHUNK_CHARS = 1_000;

    private PreviewExtractor() {
    }

    /**
     * @param type      detected format
     * @param chunks    normalized text in order; concatenated they are the first {@code chars} characters
     * @param truncated true when the document continues beyond the preview budget
     * @param pages     pages / sheets / slides reported by the extractor, or {@code -1}
     */
    public record Excerpt(DocumentType type, List<TextChunk> chunks, long chars, boolean truncated, int pages) {
        public Excerpt {
            chunks = List.copyOf(chunks);
        }
    }

    /** Magic-byte based detection, identical to ingestion. */
    public static DocumentType detect(Path file) throws IngestionException {
        return DocumentParser.detect(file, file.getFileName().toString());
    }

    /**
     * Extracts at most {@code maxChars} normalized characters.
     *
     * @param maxPdfPages last PDF page to strip (PDFBox otherwise walks every page even after the cap is hit)
     */
    public static Excerpt extract(Path file, DocumentType type, long maxChars, int maxPdfPages, Limits limits)
            throws IngestionException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Limits effective = limits == null ? Limits.DEFAULTS : limits;
        HeapGuard guard = new HeapGuard(effective.maxHeapUsageRatio());
        if (guard.underPressure()) {
            throw new IngestionException(Reason.MEMORY_PRESSURE, "Heap too full for a preview (" + guard.describe() + ")");
        }
        TextChunker chunker = new TextChunker(PREVIEW_CHUNK_CHARS, Math.max(PREVIEW_CHUNK_CHARS, maxChars), guard);
        int pages = -1;
        String name = file.getFileName().toString();
        switch (type) {
            case PDF -> pages = PdfTextExtractor.extract(file, chunker, maxPdfPages).pageCount();
            case DOCX -> DocxTextExtractor.extract(file, chunker, effective);
            case XLSX -> pages = XlsxTextExtractor.extract(file, chunker, effective).sheets();
            case PPTX -> pages = PptxTextExtractor.extract(file, chunker, effective).slides();
            case PPT -> pages = PptTextExtractor.extract(file, chunker, effective).slides();
            case DOC -> DocTextExtractor.extract(file, chunker);
            case XLS -> pages = XlsTextExtractor.extract(file, chunker, effective).sheets();
            case CSV -> CsvTextExtractor.extract(file, name, chunker, effective);
            case TXT -> PlainTextExtractor.extract(file, chunker);
        }
        boolean pdfCut = type == DocumentType.PDF && pages > maxPdfPages;
        List<TextChunk> chunks = chunker.finish();
        return new Excerpt(type, chunks, chunker.totalChars(), chunker.truncated() || pdfCut, pages);
    }
}
