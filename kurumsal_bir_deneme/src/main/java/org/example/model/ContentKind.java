package org.example.model;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The two tracks content can take through the engine.
 *
 * <ul>
 *   <li>{@link #TEXT}: a {@link DocumentType} document; extracted, chunked and put into the inverted index.</li>
 *   <li>{@link #BINARY}: media, images, archives and other blobs; never parsed, never indexed, only registered in
 *       the content store by SHA-256 so peers can fetch the raw bytes.</li>
 * </ul>
 */
public enum ContentKind {

    TEXT,
    BINARY;

    /** Extensions that are always treated as raw blobs, whatever their bytes look like. */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            // video (plain ".ts" is left to sniffing: it is also TypeScript source)
            "mp4", "m4v", "mkv", "mov", "avi", "wmv", "webm", "flv", "mpg", "mpeg", "m2ts", "mts", "3gp",
            // audio
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma",
            // images
            "jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "webp", "heic", "heif", "raw", "cr2", "nef", "arw",
            "dng", "psd", "ico",
            // archives and disk images
            "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso", "img", "dmg", "cab",
            // executables and other opaque blobs
            "exe", "msi", "dll", "so", "bin", "jar", "apk", "db", "sqlite");

    /** File-chooser patterns ({@code *.mp4}, …) for every extension that is always treated as a binary asset. */
    public static java.util.List<String> binaryPatterns() {
        return BINARY_EXTENSIONS.stream().sorted().map(ext -> "*." + ext).toList();
    }

    /**
     * Classifies by name only: a {@link DocumentType} extension is {@link #TEXT}, a known media/archive extension is
     * {@link #BINARY}, anything else is undecided (the caller sniffs the bytes).
     */
    public static Optional<ContentKind> fromFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        if (DocumentType.fromFileName(fileName).isPresent()) {
            return Optional.of(TEXT);
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        return BINARY_EXTENSIONS.contains(ext) ? Optional.of(BINARY) : Optional.empty();
    }
}
