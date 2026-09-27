package org.example.model;

import org.example.util.Hashing;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, content-addressed description of one ingested document.
 *
 * @param sha256     lowercase hex SHA-256 of the raw file bytes; the document identity everywhere in the engine
 * @param fileName   display name; forks and documents received from peers carry a disambiguating tag, e.g.
 *                   {@code "plan.xlsx [beta · 2026-09-20 19:07]"}
 * @param source     local path the bytes were read from
 * @param type       detected format
 * @param sizeBytes  raw file size
 * @param pageCount  number of pages (PDF) or {@code -1}
 * @param emptyPages PDF pages without a text layer (scanned images); always 0 for other formats
 * @param charCount  length of the normalized text
 * @param ingestedAt ingestion timestamp
 * @param origin     node this content came from: {@link #LOCAL}, {@link #SYSTEM} for a built-in fixture, or the
 *                   peer name/id that sent it
 * @param chunks     normalized text split into index/prompt sized chunks
 */
public record DocumentRecord(String sha256,
                             String fileName,
                             Path source,
                             DocumentType type,
                             long sizeBytes,
                             int pageCount,
                             int emptyPages,
                             long charCount,
                             Instant ingestedAt,
                             String origin,
                             List<TextChunk> chunks) {

    /** Origin of locally ingested content. */
    public static final String LOCAL = "local";

    /**
     * Origin of built-in, read-only system fixtures (the guide's demo report). Such documents take part in BM25
     * search only: they are hidden from document lists, counts, the {@code docs} command and snapshots, and can be
     * neither untracked nor purged.
     */
    public static final String SYSTEM = "system";

    public DocumentRecord {
        if (!Hashing.isSha256Hex(sha256)) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
        }
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(ingestedAt, "ingestedAt must not be null");
        origin = origin == null || origin.isBlank() ? LOCAL : origin.strip();
        chunks = List.copyOf(chunks);
    }

    /** Same content under a different display name and origin (fork tagging, peer namespacing). */
    public DocumentRecord withNaming(String newFileName, String newOrigin) {
        return new DocumentRecord(sha256, newFileName, source, type, sizeBytes, pageCount, emptyPages, charCount,
                ingestedAt, newOrigin, chunks);
    }

    /**
     * Always {@link ContentKind#TEXT}: a record only exists for content that was extracted into the index. Binary
     * blobs are described by {@link BinaryAsset} instead.
     */
    public ContentKind contentKind() {
        return ContentKind.TEXT;
    }

    public boolean local() {
        return LOCAL.equals(origin);
    }

    /** True for a built-in system fixture; see {@link #SYSTEM}. */
    public boolean isSystemDocument() {
        return SYSTEM.equals(origin);
    }

    /** First 12 hex digits, enough to address a document interactively. */
    public String shortId() {
        return sha256.substring(0, 12);
    }
}
