package org.yazi.gateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Image bytes sent inside a request (image-to-prompt, reference images). */
public record InlineImage(String mimeType, byte[] data) {

    public InlineImage {
        Objects.requireNonNull(mimeType, "mimeType");
        if (!mimeType.startsWith("image/")) {
            throw new IllegalArgumentException("not an image mime type: " + mimeType);
        }
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            throw new IllegalArgumentException("image data is empty");
        }
        data = data.clone();
    }

    public static InlineImage fromFile(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        String mime;
        if (name.endsWith(".png")) {
            mime = "image/png";
        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            mime = "image/jpeg";
        } else if (name.endsWith(".webp")) {
            mime = "image/webp";
        } else if (name.endsWith(".heic")) {
            mime = "image/heic";
        } else if (name.endsWith(".heif")) {
            mime = "image/heif";
        } else {
            throw new IOException("Unsupported image type: " + path.getFileName());
        }
        return new InlineImage(mime, Files.readAllBytes(path));
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof InlineImage other && mimeType.equals(other.mimeType) && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return 31 * mimeType.hashCode() + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "InlineImage{" + mimeType + ", " + data.length + " bytes}";
    }
}
