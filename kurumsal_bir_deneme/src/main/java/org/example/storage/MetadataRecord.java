package org.example.storage;

import org.example.model.DocumentRecord;
import org.example.model.DocumentType;
import org.example.model.TextChunk;
import org.example.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Persistent metadata of one indexed document: identity, provenance, the source file's fingerprint at ingestion time
 * and the coordinates of every chunk. Chunk text is stored next to it in the snapshot block, not in this record, so
 * metadata can be inspected (e.g. for staleness checks) without touching text.
 *
 * @param sha256             lowercase hex SHA-256 of the raw file bytes
 * @param fileName           display name (fork/peer tags included)
 * @param source             path the bytes were read from, as a string so non-default file systems round-trip
 * @param type               detected format
 * @param sizeBytes          raw file size at ingestion
 * @param pageCount          page count or {@code -1}
 * @param emptyPages         pages without a text layer
 * @param charCount          normalized text length
 * @param ingestedAtMillis   ingestion time, epoch millis
 * @param origin             {@link DocumentRecord#LOCAL} or a peer name
 * @param sourceModifiedMillis last-modified time of {@code source} observed at ingestion, or {@code -1}
 * @param parseMillis        extraction latency measured at ingestion, or {@code -1} when unknown
 * @param chunks             chunk coordinates in chunk order
 */
public record MetadataRecord(String sha256,
                             String fileName,
                             String source,
                             DocumentType type,
                             long sizeBytes,
                             int pageCount,
                             int emptyPages,
                             long charCount,
                             long ingestedAtMillis,
                             String origin,
                             long sourceModifiedMillis,
                             long parseMillis,
                             List<ChunkOffset> chunks) {

    /**
     * Coordinates of one chunk inside the normalized document text.
     *
     * @param index       chunk ordinal within the document
     * @param page        page the chunk starts on, or {@code -1}
     * @param startOffset character offset of the chunk's first character
     * @param length      chunk length in characters
     */
    public record ChunkOffset(int index, int page, long startOffset, int length) {
        public ChunkOffset {
            if (index < 0 || startOffset < 0 || length < 0) {
                throw new IllegalArgumentException("chunk coordinates must not be negative");
            }
        }
    }

    public MetadataRecord {
        if (!Hashing.isSha256Hex(sha256)) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
        }
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(type, "type must not be null");
        origin = origin == null || origin.isBlank() ? DocumentRecord.LOCAL : origin;
        chunks = List.copyOf(chunks);
    }

    /**
     * Captures {@code document}'s metadata with the source fingerprint taken now. Prefer
     * {@link #of(DocumentRecord, long, long)} with the time observed at ingestion: a file edited between ingestion
     * and snapshot would otherwise be recorded as fresh.
     */
    public static MetadataRecord of(DocumentRecord document, long parseMillis) {
        return of(document, parseMillis, modifiedMillis(document.source()));
    }

    /** Captures {@code document}'s metadata with an explicit source modification time ({@code -1} if unknown). */
    public static MetadataRecord of(DocumentRecord document, long parseMillis, long sourceModifiedMillis) {
        List<ChunkOffset> offsets = new ArrayList<>(document.chunks().size());
        for (TextChunk chunk : document.chunks()) {
            offsets.add(new ChunkOffset(chunk.index(), chunk.page(), chunk.startOffset(), chunk.text().length()));
        }
        return new MetadataRecord(document.sha256(), document.fileName(), document.source().toString(),
                document.type(), document.sizeBytes(), document.pageCount(), document.emptyPages(),
                document.charCount(), document.ingestedAt().toEpochMilli(), document.origin(),
                sourceModifiedMillis, parseMillis, offsets);
    }

    /** Reassembles the engine record; {@code texts} must be in chunk order and match {@link #chunks()} in size. */
    public DocumentRecord toDocument(List<String> texts) {
        if (texts.size() != chunks.size()) {
            throw new IllegalArgumentException("expected " + chunks.size() + " chunk texts, got " + texts.size());
        }
        List<TextChunk> rebuilt = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkOffset c = chunks.get(i);
            String text = texts.get(i);
            if (text.length() != c.length()) {
                throw new IllegalArgumentException("chunk " + c.index() + " length mismatch");
            }
            rebuilt.add(new TextChunk(c.index(), c.page(), c.startOffset(), text));
        }
        return new DocumentRecord(sha256, fileName, Path.of(source), type, sizeBytes, pageCount, emptyPages,
                charCount, Instant.ofEpochMilli(ingestedAtMillis), origin, rebuilt);
    }

    public boolean local() {
        return DocumentRecord.LOCAL.equals(origin);
    }

    /** Current state of the source file relative to this snapshot. */
    public enum Freshness {
        /** Same size and modification time as when the snapshot was written. */
        FRESH,
        /** Exists but size or modification time differs: the indexed text may be outdated. */
        CHANGED,
        /** Deleted: its folder is still there but the file is not. */
        MISSING,
        /** No fingerprint was recorded, or the source is unreachable right now; nothing can be concluded. */
        UNKNOWN
    }

    public Freshness freshness() {
        if (sourceModifiedMillis < 0) {
            return Freshness.UNKNOWN;
        }
        Path path;
        try {
            path = Path.of(source);
        } catch (RuntimeException e) {
            return Freshness.MISSING;
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return attrs.size() == sizeBytes && attrs.lastModifiedTime().toMillis() == sourceModifiedMillis
                    ? Freshness.FRESH : Freshness.CHANGED;
        } catch (NoSuchFileException e) {
            // Gone only if the volume and folder are there: an unplugged drive, an offline network share or an
            // unmounted folder makes a file unreachable, not deleted.
            Path parent = path.toAbsolutePath().getParent();
            return parent != null && Files.isDirectory(parent) ? Freshness.MISSING : Freshness.UNKNOWN;
        } catch (IOException | RuntimeException e) {
            // permission problems, locks, network errors: the document is kept as indexed
            return Freshness.UNKNOWN;
        }
    }

    /** Last-modified time of {@code path} in epoch millis, or {@code -1} when it cannot be read. */
    public static long modifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
