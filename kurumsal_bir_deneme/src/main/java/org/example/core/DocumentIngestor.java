package org.example.core;

import org.example.model.BinaryAsset;
import org.example.model.DocumentRecord;

import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Port: turns a local file into a content-addressed {@link DocumentRecord}.
 */
public interface DocumentIngestor {

    /** Cheap pre-check (extension based) used when walking directories: documents and binary media alike. */
    boolean supports(Path file);

    /**
     * Hashes the file first and returns {@link IngestResult.Duplicate} without extracting text when
     * {@code isKnown} accepts the hash. Binary media (video, images, audio, archives) are not parsed at all: they
     * come back as {@link IngestResult.Registered} for the content store and never reach the text index.
     *
     * @param displayName name to record instead of the file name (e.g. original name of a peer file), may be null
     */
    IngestResult ingest(Path file, String displayName, Predicate<String> isKnown) throws IngestionException;

    /** Result of an ingestion attempt that did not fail. */
    sealed interface IngestResult permits IngestResult.Ingested, IngestResult.Duplicate, IngestResult.Registered {

        String sha256();

        record Ingested(DocumentRecord document, long elapsedMillis) implements IngestResult {
            @Override
            public String sha256() {
                return document.sha256();
            }
        }

        record Duplicate(String sha256, Path file) implements IngestResult {
        }

        /** A binary blob, hashed (streaming) but not parsed; to be registered for raw distribution only. */
        record Registered(BinaryAsset asset, long elapsedMillis) implements IngestResult {
            @Override
            public String sha256() {
                return asset.sha256();
            }
        }
    }
}
