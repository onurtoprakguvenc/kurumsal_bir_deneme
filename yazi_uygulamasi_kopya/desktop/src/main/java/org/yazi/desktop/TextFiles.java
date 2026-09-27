package org.yazi.desktop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Reads and writes documents so that opening and saving a file changes nothing the writer did not change.
 *
 * <ul>
 *   <li><b>Encoding.</b> UTF-8 first; a UTF-8 or UTF-16 byte-order mark is honoured and remembered. Text that is
 *       not valid UTF-8 is read as windows-1254 (the Turkish Windows code page, which covers ASCII and most Western
 *       text), and as ISO-8859-1 as a last resort, which never fails.</li>
 *   <li><b>Line endings.</b> The editor only knows {@code \n} (RichTextFX folds {@code \r\n} on insert), so the
 *       file's convention is recorded on open and restored on save.</li>
 *   <li><b>Binary files</b> (a NUL byte near the start, with no UTF-16 mark) are refused instead of opened as
 *       garbage.</li>
 *   <li><b>Saving is atomic.</b> The text goes to a temporary file next to the target, which then replaces it, so
 *       a failed save (disk full, lost network drive) leaves the old file intact.</li>
 * </ul>
 *
 * <p>Stateless; the {@link Format} travels with the document.</p>
 */
final class TextFiles {

    private TextFiles() {}

    private static final Charset WINDOWS_1254 = Charset.forName("windows-1254");
    private static final int BINARY_PROBE_BYTES = 8_192;

    /** How a document is stored on disk. */
    record Format(Charset charset, boolean bom, String lineSeparator) {

        /** New documents: UTF-8, no BOM, {@code \n}, as the editor has always saved them. */
        static final Format DEFAULT = new Format(StandardCharsets.UTF_8, false, "\n");

        Format {
            Objects.requireNonNull(charset, "charset");
            if (!"\n".equals(lineSeparator) && !"\r\n".equals(lineSeparator)) {
                throw new IllegalArgumentException("unsupported line separator");
            }
        }

        boolean isDefault() {
            return equals(DEFAULT);
        }

        /** Always-on status-bar label, e.g. {@code "UTF-8 · LF"} or {@code "windows-1254 · CRLF"}. */
        String shortLabel() {
            String name = charset.equals(StandardCharsets.UTF_8) ? "UTF-8" : charset.name();
            return name + (bom ? " BOM" : "") + " · " + ("\r\n".equals(lineSeparator) ? "CRLF" : "LF");
        }

        /** For the status message on open, e.g. {@code "windows-1254, CRLF"}; empty for the default format. */
        String describe() {
            if (isDefault()) {
                return "";
            }
            String name = charset.equals(StandardCharsets.UTF_8) ? "UTF-8" : charset.name();
            return name + (bom ? " with BOM" : "") + ("\r\n".equals(lineSeparator) ? ", CRLF" : ", LF");
        }
    }

    record Decoded(String text, Format format) {}

    /** Bytes to write, and the format they are actually in (see {@link #encode}). */
    record Encoded(byte[] bytes, Format format, boolean fellBackToUtf8) {}

    /** Thrown for a file that does not look like text. */
    static final class NotTextException extends IOException {
        NotTextException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------------------------------------

    static Decoded read(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    static Decoded decode(byte[] bytes) throws NotTextException {
        Charset charset;
        boolean bom;
        int skip;
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            charset = StandardCharsets.UTF_8;
            bom = true;
            skip = 3;
        } else if (startsWith(bytes, 0xFF, 0xFE)) {
            charset = StandardCharsets.UTF_16LE;
            bom = true;
            skip = 2;
        } else if (startsWith(bytes, 0xFE, 0xFF)) {
            charset = StandardCharsets.UTF_16BE;
            bom = true;
            skip = 2;
        } else {
            if (looksBinary(bytes)) {
                throw new NotTextException("This does not look like a text file (it contains binary data).");
            }
            bom = false;
            skip = 0;
            charset = null;
        }

        String raw;
        if (charset != null) {
            raw = new String(bytes, skip, bytes.length - skip, charset);
        } else {
            raw = strictDecode(bytes, StandardCharsets.UTF_8);
            charset = StandardCharsets.UTF_8;
            if (raw == null) {
                raw = strictDecode(bytes, WINDOWS_1254);
                charset = WINDOWS_1254;
            }
            if (raw == null) {
                raw = new String(bytes, StandardCharsets.ISO_8859_1);
                charset = StandardCharsets.ISO_8859_1;
            }
        }
        String separator = dominantSeparator(raw);
        return new Decoded(normalizeLineEndings(raw), new Format(charset, bom, separator));
    }

    /** {@code \r\n} and lone {@code \r} become {@code \n}, which is what the editor stores. */
    static String normalizeLineEndings(String text) {
        return text.indexOf('\r') < 0 ? text : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** CRLF when most line breaks are CRLF; otherwise LF. */
    static String dominantSeparator(String text) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (i > 0 && text.charAt(i - 1) == '\r') {
                    crlf++;
                } else {
                    lf++;
                }
            }
        }
        return crlf > lf ? "\r\n" : "\n";
    }

    // ------------------------------------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------------------------------------

    /**
     * Encodes editor text (always {@code \n}) in {@code format}. When the text holds characters that the format's
     * charset cannot store (an emoji in a windows-1254 file), it is written as UTF-8 instead of losing them, and
     * {@link Encoded#fellBackToUtf8()} is set.
     */
    static Encoded encode(String text, Format format) {
        String withSeparators = "\r\n".equals(format.lineSeparator()) ? text.replace("\n", "\r\n") : text;
        byte[] body = strictEncode(withSeparators, format.charset());
        Format used = format;
        boolean fellBack = false;
        if (body == null) {
            used = new Format(StandardCharsets.UTF_8, false, format.lineSeparator());
            body = withSeparators.getBytes(StandardCharsets.UTF_8);
            fellBack = true;
        }
        byte[] bom = used.bom() ? bomFor(used.charset()) : new byte[0];
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return new Encoded(out, used, fellBack);
    }

    /** Encodes and writes atomically; returns the format actually written. */
    static Encoded write(Path target, String text, Format format) throws IOException {
        Encoded encoded = encode(text, format);
        writeAtomically(target, encoded.bytes());
        return encoded;
    }

    /**
     * Writes to a temporary sibling, then moves it over {@code target}. A symbolic link is followed so the link
     * itself survives. A read-only target is refused up front, as a plain write would have been.
     */
    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path real = Files.isSymbolicLink(target) ? target.toRealPath() : target.toAbsolutePath();
        if (Files.exists(real) && !Files.isWritable(real)) {
            throw new AccessDeniedException(real.toString(), null, "read-only");
        }
        Path dir = real.getParent();
        Path temp = Files.createTempFile(dir, "." + real.getFileName(), ".yazi-tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, real, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, real, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------------------------------------

    /** A sentence a writer can act on, instead of an exception's raw message (often just a path). */
    static String describe(IOException e) {
        return switch (e) {
            case NotTextException n -> n.getMessage();
            case NoSuchFileException n -> "The file or its folder no longer exists.";
            case AccessDeniedException a ->
                    "Access denied. The file may be read-only, or open in another program.";
            case FileSystemException f when f.getReason() != null -> f.getReason();
            default -> e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        };
    }

    // ------------------------------------------------------------------------------------------------

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksBinary(byte[] bytes) {
        int n = Math.min(bytes.length, BINARY_PROBE_BYTES);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String strictDecode(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static byte[] strictEncode(String text, Charset charset) {
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(text));
            byte[] out = new byte[buffer.remaining()];
            buffer.get(out);
            return out;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static byte[] bomFor(Charset charset) {
        if (charset.equals(StandardCharsets.UTF_16LE)) {
            return new byte[]{(byte) 0xFF, (byte) 0xFE};
        }
        if (charset.equals(StandardCharsets.UTF_16BE)) {
            return new byte[]{(byte) 0xFE, (byte) 0xFF};
        }
        return new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    }
}
