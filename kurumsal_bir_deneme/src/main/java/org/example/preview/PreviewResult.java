package org.example.preview;

import org.example.core.IngestionException;
import org.example.model.DocumentType;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Outcome of a preview request. Only {@link Ready} carries content; every other case is a cheap, allocation-light
 * notice the UI renders instead of a document.
 */
public sealed interface PreviewResult permits PreviewResult.Ready, PreviewResult.TooLarge, PreviewResult.Unsupported,
        PreviewResult.Failed, PreviewResult.Superseded {

    enum Status {
        READY, TOO_LARGE, UNSUPPORTED, FAILED, SUPERSEDED
    }

    Path path();

    /** Bounded content; the caller owns it and must {@link PreviewDocument#close() close} it when dismissed. */
    record Ready(Path path, PreviewDocument document) implements PreviewResult {
    }

    /**
     * Refused by the preflight size guard before any byte was parsed.
     *
     * @param sizeBytes  exact file size as reported by the file system
     * @param limitBytes inline preview limit for {@code type}
     */
    record TooLarge(Path path, DocumentType type, long sizeBytes, long limitBytes) implements PreviewResult {
    }

    record Unsupported(Path path, String reason) implements PreviewResult {
    }

    record Failed(Path path, IngestionException.Reason reason, String message) implements PreviewResult {
    }

    /** A newer preview request replaced this one before it completed; any partial content was released. */
    record Superseded(Path path) implements PreviewResult {
    }

    default Status status() {
        return switch (this) {
            case Ready r -> Status.READY;
            case TooLarge t -> Status.TOO_LARGE;
            case Unsupported u -> Status.UNSUPPORTED;
            case Failed f -> Status.FAILED;
            case Superseded s -> Status.SUPERSEDED;
        };
    }

    /** One-line, user-presentable description. */
    default String describe() {
        String name = path() == null || path().getFileName() == null ? "?" : path().getFileName().toString();
        return switch (this) {
            case Ready r -> String.format(Locale.ROOT, "READY %s: %d lines, %d chars%s", name,
                    r.document().lineCount(), r.document().retainedChars(),
                    r.document().truncated() ? " (beginning only)" : "");
            case TooLarge t -> String.format(Locale.ROOT,
                    "TOO_LARGE %s: %s (%,d bytes) exceeds the %s inline preview limit for %s; open it instead",
                    name, human(t.sizeBytes()), t.sizeBytes(), human(t.limitBytes()),
                    t.type().extension().toUpperCase(Locale.ROOT));
            case Unsupported u -> "UNSUPPORTED " + name + ": " + u.reason();
            case Failed f -> "FAILED " + name + " (" + f.reason() + "): " + f.message();
            case Superseded s -> "SUPERSEDED " + name;
        };
    }

    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
