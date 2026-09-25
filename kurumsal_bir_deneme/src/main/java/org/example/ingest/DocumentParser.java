package org.example.ingest;

import org.example.core.DocumentIngestor;
import org.example.core.HeapGuard;
import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;
import org.example.model.BinaryAsset;
import org.example.model.ContentKind;
import org.example.model.DocumentRecord;
import org.example.model.DocumentType;
import org.example.model.TextChunk;
import org.example.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * PDF / DOCX / XLSX / PPTX / PPT / DOC / XLS / CSV / TXT ingestion adapter.
 *
 * <p>Pipeline: validate path → stream SHA-256 (64 KiB blocks) → skip if the hash is already known → detect the
 * format from magic bytes and package structure (extension only as a tie-breaker for text formats) → stream text
 * into a {@link TextChunker} → verify the file did not change while being read → immutable
 * {@link DocumentRecord}.</p>
 *
 * <p>A {@link HeapGuard} sits in front of every file and inside the chunker: ingestion is refused when the heap is
 * already above {@link Limits#maxHeapUsageRatio()}, and a running extraction stops gracefully with a note rather
 * than exhausting the heap.</p>
 *
 * <p>Spreadsheets (XLSX, CSV/TSV) are rendered as coordinate-aware Markdown table blocks sized below the chunk
 * size, so every chunk carries its own {@code Row n} / column-letter header. Office Open XML formats are read
 * with {@link java.util.zip.ZipFile} and StAX only; legacy PowerPoint 97-2003 (.ppt) OLE2 files are read natively
 * by {@link PptTextExtractor}. Page coordinates: PDF pages, XLSX worksheets (1-based sheet
 * position) and PPTX/PPT slides (1-based presentation position) each start a new page, and the record's page count
 * is the number of pages, sheets or slides respectively.</p>
 *
 * <p>Binary media and blobs (video, images, archives, …) take a separate track: {@link #classify} recognizes them
 * and {@link #describeBinary} hashes them for the content store without any text extraction or size limit, so they
 * never reach the inverted index and are never rejected as "binary, not text".</p>
 */
public final class DocumentParser implements DocumentIngestor {

    public static final long DEFAULT_MAX_FILE_BYTES = 512L * 1024 * 1024;
    public static final int DEFAULT_CHUNK_CHARS = 1_200;

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final int SNIFF_BYTES = 1_024;

    private final long maxFileBytes;
    private final int chunkChars;
    private final Limits limits;

    public DocumentParser() {
        this(DEFAULT_MAX_FILE_BYTES, DEFAULT_CHUNK_CHARS, Limits.DEFAULTS);
    }

    public DocumentParser(long maxFileBytes, int chunkChars) {
        this(maxFileBytes, chunkChars, Limits.DEFAULTS);
    }

    /**
     * @param limits structured-format limits; their table block size is aligned to three quarters of
     *               {@code chunkChars} so a table block never straddles two chunks
     */
    public DocumentParser(long maxFileBytes, int chunkChars, Limits limits) {
        if (maxFileBytes <= 0) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }
        this.maxFileBytes = maxFileBytes;
        this.chunkChars = chunkChars;
        this.limits = Objects.requireNonNull(limits, "limits must not be null")
                .withTableBlockChars(Math.max(200, chunkChars * 3 / 4));
    }

    @Override
    public boolean supports(Path file) {
        return file != null && file.getFileName() != null
                && ContentKind.fromFileName(file.getFileName().toString()).isPresent();
    }

    @Override
    public IngestResult ingest(Path file, String displayName, Predicate<String> isKnown) throws IngestionException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(isKnown, "isKnown must not be null");
        long started = System.nanoTime();

        Path real = realPath(file);
        BasicFileAttributes before = attributes(real);
        if (!before.isRegularFile()) {
            throw new IngestionException(Reason.NOT_A_FILE, real + " is not a regular file");
        }
        if (classify(real, displayName == null || displayName.isBlank() ? null : displayName.strip())
                == ContentKind.BINARY) {
            // Media and archives bypass extraction and the text size limit: one streaming hash, then the store.
            BinaryAsset asset = describeBinary(real, displayName, null, null);
            return new IngestResult.Registered(asset, (System.nanoTime() - started) / 1_000_000);
        }
        if (before.size() > maxFileBytes) {
            throw new IngestionException(Reason.TOO_LARGE, "File is " + (before.size() >> 20) + " MB; limit is "
                    + (maxFileBytes >> 20) + " MB");
        }
        if (before.size() == 0) {
            throw new IngestionException(Reason.EMPTY, "File is empty");
        }

        String sha256;
        try {
            sha256 = Hashing.sha256Hex(real);
        } catch (AccessDeniedException e) {
            throw new IngestionException(Reason.ACCESS_DENIED, "Permission denied: " + real, e);
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot hash " + real + ": " + e.getMessage(), e);
        }
        if (isKnown.test(sha256)) {
            return new IngestResult.Duplicate(sha256, real);
        }

        String name = displayName == null || displayName.isBlank() ? real.getFileName().toString() : displayName.strip();
        DocumentType type = detect(real, name);
        HeapGuard heapGuard = new HeapGuard(limits.maxHeapUsageRatio());
        if (heapGuard.underPressure()) {
            throw new IngestionException(Reason.MEMORY_PRESSURE,
                    "Heap pressure too high to ingest " + name + " (" + heapGuard.describe() + ")");
        }
        TextChunker chunker = new TextChunker(chunkChars, TextChunker.DEFAULT_MAX_TOTAL_CHARS, heapGuard);
        int pageCount = -1;
        int emptyPages = 0;
        switch (type) {
            case PDF -> {
                PdfTextExtractor.Info info = PdfTextExtractor.extract(real, chunker);
                pageCount = info.pageCount();
                emptyPages = info.emptyPages();
            }
            case DOCX -> DocxTextExtractor.extract(real, chunker, limits);
            case XLSX -> pageCount = XlsxTextExtractor.extract(real, chunker, limits).sheets();
            case PPTX -> pageCount = PptxTextExtractor.extract(real, chunker, limits).slides();
            case PPT -> pageCount = PptTextExtractor.extract(real, chunker, limits).slides();
            case DOC -> DocTextExtractor.extract(real, chunker);
            case XLS -> pageCount = XlsTextExtractor.extract(real, chunker, limits).sheets();
            case CSV -> CsvTextExtractor.extract(real, name, chunker, limits);
            case TXT -> PlainTextExtractor.extract(real, chunker);
        }
        if (chunker.truncated() && !chunker.memoryTruncated()) {
            chunker.noteCharacterLimit();
        }

        BasicFileAttributes after = attributes(real);
        if (after.size() != before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime())) {
            throw new IngestionException(Reason.CHANGED_DURING_READ, "File changed while it was being ingested; retry");
        }

        List<TextChunk> chunks = chunker.finish();
        if (chunks.isEmpty() && chunker.memoryTruncated()) {
            throw new IngestionException(Reason.MEMORY_PRESSURE,
                    "Heap pressure stopped the extraction of " + name + " before any text was produced");
        }
        if (chunks.isEmpty()) {
            if (type == DocumentType.PDF && pageCount > 0 && emptyPages == pageCount) {
                throw new IngestionException(Reason.EMPTY, "PDF has no text layer (" + pageCount
                        + " scanned page(s)); OCR is not part of this engine");
            }
            throw new IngestionException(Reason.EMPTY, "Document contains no extractable text");
        }
        DocumentRecord record = new DocumentRecord(sha256, name, real, type, before.size(), pageCount, emptyPages,
                chunker.totalChars(), Instant.now(), DocumentRecord.LOCAL, chunks);
        return new IngestResult.Ingested(record, (System.nanoTime() - started) / 1_000_000);
    }

    // ------------------------------------------------------------------ binary track

    /**
     * Decides which track a file takes. The name decides first ({@link ContentKind#fromFileName}): document
     * extensions stay {@link ContentKind#TEXT} exactly as before, known media/archive extensions are
     * {@link ContentKind#BINARY}. For any other name the first bytes decide: a document signature (PDF, OOXML, OLE2
     * Office) is text, a media/archive signature or NUL bytes that are not UTF-16 text is binary. Never throws: an
     * unreadable file is reported as text so {@link #ingest} produces the precise error.
     */
    public static ContentKind classify(Path file, String name) {
        String label = name != null ? name : file.getFileName() == null ? "" : file.getFileName().toString();
        Optional<ContentKind> byName = ContentKind.fromFileName(label);
        if (byName.isPresent()) {
            return byName.get();
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(SNIFF_BYTES);
        } catch (IOException | RuntimeException e) {
            return ContentKind.TEXT;
        }
        if (startsWith(head, PDF_MAGIC)) {
            return ContentKind.TEXT;
        }
        if (startsWith(head, ZIP_MAGIC)) {
            try {
                return ooxmlKind(file).isPresent() ? ContentKind.TEXT : ContentKind.BINARY;
            } catch (IngestionException e) {
                return ContentKind.BINARY;
            }
        }
        if (startsWith(head, PptTextExtractor.OLE2_MAGIC)) {
            return PptTextExtractor.isPowerPoint(file) || legacyOfficeKind(file).isPresent()
                    ? ContentKind.TEXT : ContentKind.BINARY;
        }
        if (hasMediaSignature(head)) {
            return ContentKind.BINARY;
        }
        try {
            TextEncoding.sniff(file);
            return ContentKind.TEXT;
        } catch (IngestionException e) {
            return e.reason() == Reason.UNSUPPORTED ? ContentKind.BINARY : ContentKind.TEXT;
        }
    }

    /**
     * Registers a binary blob without touching its bytes beyond one streaming SHA-256 pass (64 KiB blocks, constant
     * memory, any size). No text extraction, no size limit, no inverted index.
     *
     * @param hashedBytes receives the running byte count while hashing; may be null
     */
    public BinaryAsset describeBinary(Path file, String displayName, String origin, LongConsumer hashedBytes)
            throws IngestionException {
        Objects.requireNonNull(file, "file must not be null");
        Path real = realPath(file);
        BasicFileAttributes before = attributes(real);
        if (!before.isRegularFile()) {
            throw new IngestionException(Reason.NOT_A_FILE, real + " is not a regular file");
        }
        if (before.size() == 0) {
            throw new IngestionException(Reason.EMPTY, "File is empty");
        }
        String sha256;
        try {
            sha256 = Hashing.sha256Hex(real, hashedBytes == null ? n -> { } : hashedBytes);
        } catch (AccessDeniedException e) {
            throw new IngestionException(Reason.ACCESS_DENIED, "Permission denied: " + real, e);
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot hash " + real + ": " + e.getMessage(), e);
        }
        BasicFileAttributes after = attributes(real);
        if (after.size() != before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime())) {
            throw new IngestionException(Reason.CHANGED_DURING_READ, "File changed while it was being hashed; retry");
        }
        String name = displayName == null || displayName.isBlank() ? real.getFileName().toString() : displayName.strip();
        return new BinaryAsset(sha256, name, real, before.size(), Instant.now(), origin);
    }

    /** Container signatures of common video, audio, image and archive formats. */
    private static boolean hasMediaSignature(byte[] h) {
        return (h.length >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') // MP4 / MOV / HEIC / 3GP
                || startsWith(h, new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})           // MKV / WebM (EBML)
                || startsWith(h, new byte[]{'R', 'I', 'F', 'F'})                             // AVI / WAV / WebP
                || startsWith(h, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})          // JPEG
                || startsWith(h, new byte[]{(byte) 0x89, 'P', 'N', 'G'})                     // PNG
                || startsWith(h, new byte[]{'G', 'I', 'F', '8'})                             // GIF
                || startsWith(h, new byte[]{'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C}) // 7-Zip
                || startsWith(h, new byte[]{'R', 'a', 'r', '!'})                             // RAR
                || startsWith(h, new byte[]{0x1F, (byte) 0x8B})                              // gzip
                || startsWith(h, new byte[]{(byte) 0xFD, '7', 'z', 'X', 'Z', 0})             // xz
                || startsWith(h, new byte[]{'O', 'g', 'g', 'S'})                             // Ogg
                || startsWith(h, new byte[]{'f', 'L', 'a', 'C'})                             // FLAC
                || startsWith(h, new byte[]{'I', 'D', '3'});                                 // MP3 with ID3
    }

    private static Path realPath(Path file) throws IngestionException {
        try {
            return file.toRealPath();
        } catch (NoSuchFileException e) {
            throw new IngestionException(Reason.NOT_FOUND, "No such file: " + file, e);
        } catch (AccessDeniedException e) {
            throw new IngestionException(Reason.ACCESS_DENIED, "Permission denied: " + file, e);
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot resolve " + file + ": " + e.getMessage(), e);
        }
    }

    private static BasicFileAttributes attributes(Path file) throws IngestionException {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            throw new IngestionException(Reason.NOT_FOUND, "No such file: " + file, e);
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot stat " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Magic bytes and package structure decide; the extension only selects between the text formats (TXT vs.
     * CSV/TSV) and routes OLE2 containers: PowerPoint 97-2003 to {@link PptTextExtractor}, encrypted OOXML to the
     * extractor that explains it.
     */
    static DocumentType detect(Path file, String name) throws IngestionException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(1_024);
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot read " + file + ": " + e.getMessage(), e);
        }
        Optional<DocumentType> byName = DocumentType.fromFileName(name);
        // A PDF header at offset 0 is authoritative. PDF readers also tolerate leading junk within the first KB, but
        // that is only trusted for files named .pdf: a text file merely mentioning "%PDF-" must stay text.
        if (startsWith(head, PDF_MAGIC)
                || (byName.orElse(null) == DocumentType.PDF && indexOf(head, PDF_MAGIC) >= 0
                && !startsWith(head, ZIP_MAGIC) && !startsWith(head, PptTextExtractor.OLE2_MAGIC))) {
            return DocumentType.PDF;
        }
        if (startsWith(head, ZIP_MAGIC)) {
            Optional<DocumentType> byContent = ooxmlKind(file);
            if (byContent.isPresent()) {
                return byContent.get();
            }
            if (byName.isPresent() && isOoxml(byName.get())) {
                return byName.get();
            }
            throw new IngestionException(Reason.UNSUPPORTED,
                    "ZIP archive is not a Word, Excel or PowerPoint document");
        }
        if (startsWith(head, PptTextExtractor.OLE2_MAGIC)) {
            // Legacy PowerPoint is recognized by its document stream, so a renamed .ppt still works; other OLE2
            // containers (encrypted OOXML, .doc/.xls) go to the extractor that explains them.
            if (byName.orElse(null) == DocumentType.PPT || PptTextExtractor.isPowerPoint(file)) {
                return DocumentType.PPT;
            }
            // Word and Excel 97-2003 are recognized by their main stream, so renamed files work too.
            Optional<DocumentType> legacy = legacyOfficeKind(file);
            if (legacy.isPresent()) {
                return legacy.get();
            }
            if (byName.isPresent() && isOoxml(byName.get())) {
                return byName.get();
            }
            throw new IngestionException(Reason.UNSUPPORTED, "This OLE2 file is not a Word, Excel or PowerPoint"
                    + " 97-2003 document (other legacy formats are not supported)");
        }
        if (byName.isPresent() && isOoxml(byName.get())) {
            return byName.get();
        }
        if (byName.isPresent() && byName.get() == DocumentType.PDF) {
            throw new IngestionException(Reason.CORRUPTED, "File has a .pdf name but no PDF header");
        }
        if (byName.isPresent() && byName.get() == DocumentType.PPT) {
            throw new IngestionException(Reason.CORRUPTED, "File has a .ppt name but no OLE2 (PowerPoint 97-2003) header");
        }
        return byName.filter(t -> t == DocumentType.CSV).orElse(DocumentType.TXT);
    }

    /** Word ({@code WordDocument}) or Excel ({@code Workbook}, or Excel 5/95 {@code Book}) inside an OLE2 container. */
    private static Optional<DocumentType> legacyOfficeKind(Path file) {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.READ)) {
            PptTextExtractor.CompoundFile cfb = PptTextExtractor.CompoundFile.open(channel);
            if (cfb.find("WordDocument") != null) {
                return Optional.of(DocumentType.DOC);
            }
            if (cfb.find("Workbook") != null || cfb.find("Book") != null) {
                return Optional.of(DocumentType.XLS);
            }
            return Optional.empty();
        } catch (IOException | IngestionException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Identifies an OOXML package by its main part, reading only the ZIP central directory. */
    private static Optional<DocumentType> ooxmlKind(Path file) throws IngestionException {
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            if (zip.getEntry("xl/workbook.xml") != null) {
                return Optional.of(DocumentType.XLSX);
            }
            if (zip.getEntry("word/document.xml") != null) {
                return Optional.of(DocumentType.DOCX);
            }
            if (zip.getEntry("ppt/presentation.xml") != null) {
                return Optional.of(DocumentType.PPTX);
            }
            return Optional.empty();
        } catch (ZipException e) {
            throw new IngestionException(Reason.CORRUPTED, "Corrupted ZIP archive: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static boolean isOoxml(DocumentType type) {
        return type == DocumentType.DOCX || type == DocumentType.XLSX || type == DocumentType.PPTX;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
