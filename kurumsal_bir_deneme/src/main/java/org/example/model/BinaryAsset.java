package org.example.model;

import org.example.util.Hashing;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Content-addressed description of a binary blob (video, image, archive, …). Unlike a {@link DocumentRecord} it has
 * no text and never enters the inverted index; it exists so the file can be registered and distributed by hash.
 *
 * @param sha256       lowercase hex SHA-256 of the raw bytes
 * @param fileName     display name
 * @param source       local path the bytes live at
 * @param sizeBytes    raw file size
 * @param registeredAt registration timestamp
 * @param origin       {@link DocumentRecord#LOCAL} or the peer the bytes came from
 */
public record BinaryAsset(String sha256, String fileName, Path source, long sizeBytes, Instant registeredAt,
                          String origin) {

    public BinaryAsset {
        if (!Hashing.isSha256Hex(sha256)) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
        }
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(registeredAt, "registeredAt must not be null");
        origin = origin == null || origin.isBlank() ? DocumentRecord.LOCAL : origin.strip();
    }

    public ContentKind kind() {
        return ContentKind.BINARY;
    }

    /** First 12 hex digits, enough to address the asset interactively. */
    public String shortId() {
        return sha256.substring(0, 12);
    }
}
