package org.yazi.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Open/save must not change anything the writer did not change: encoding, BOM, line endings, the file itself. */
class TextFilesTest {

    private static final Charset CP1254 = Charset.forName("windows-1254");
    private static final String TURKISH = "Çalışma günlüğü: ığdır, şöyle, İstanbul.";

    @TempDir
    Path dir;

    private static byte[] roundTrip(byte[] original) throws IOException {
        TextFiles.Decoded d = TextFiles.decode(original);
        return TextFiles.encode(d.text(), d.format()).bytes();
    }

    // --- decoding ----------------------------------------------------------------------------------

    @Test
    void plainUtf8IsTheDefaultFormat() throws IOException {
        TextFiles.Decoded d = TextFiles.decode(TURKISH.getBytes(StandardCharsets.UTF_8));
        assertEquals(TURKISH, d.text());
        assertTrue(d.format().isDefault());
        assertEquals("", d.format().describe());
    }

    @Test
    void emptyFileIsDefaultUtf8() throws IOException {
        TextFiles.Decoded d = TextFiles.decode(new byte[0]);
        assertEquals("", d.text());
        assertTrue(d.format().isDefault());
    }

    @Test
    void windows1254FileOpensInsteadOfFailing() throws IOException {
        // Before: Files.readString(..., UTF_8) threw MalformedInputException "Input length = 1".
        TextFiles.Decoded d = TextFiles.decode(TURKISH.getBytes(CP1254));
        assertEquals(TURKISH, d.text());
        assertEquals(CP1254, d.format().charset());
        assertEquals("windows-1254, LF", d.format().describe());
    }

    @Test
    void utf8BomIsStrippedFromTheTextAndRestoredOnSave() throws IOException {
        byte[] original = ("\uFEFF" + "Merhaba").getBytes(StandardCharsets.UTF_8);
        TextFiles.Decoded d = TextFiles.decode(original);
        assertEquals("Merhaba", d.text(), "the BOM must not reach the editor (or the model)");
        assertTrue(d.format().bom());
        assertEquals("UTF-8 with BOM, LF", d.format().describe());
        assertArrayEquals(original, roundTrip(original));
    }

    @Test
    void notepadUtf16WithBomRoundTrips() throws IOException {
        byte[] body = "Satır bir\r\nSatır iki".getBytes(StandardCharsets.UTF_16LE);
        byte[] original = new byte[body.length + 2];
        original[0] = (byte) 0xFF;
        original[1] = (byte) 0xFE;
        System.arraycopy(body, 0, original, 2, body.length);

        TextFiles.Decoded d = TextFiles.decode(original);
        assertEquals("Satır bir\nSatır iki", d.text());
        assertEquals(StandardCharsets.UTF_16LE, d.format().charset());
        assertArrayEquals(original, roundTrip(original));
    }

    @Test
    void crlfIsNormalisedForTheEditorAndRestoredByteForByte() throws IOException {
        byte[] original = "one\r\ntwo\r\n\r\nthree\r\n".getBytes(StandardCharsets.UTF_8);
        TextFiles.Decoded d = TextFiles.decode(original);
        assertEquals("one\ntwo\n\nthree\n", d.text());
        assertEquals("\r\n", d.format().lineSeparator());
        assertArrayEquals(original, roundTrip(original));
    }

    @Test
    void windows1254WithCrlfRoundTripsByteForByte() throws IOException {
        byte[] original = (TURKISH + "\r\n" + TURKISH + "\r\n").getBytes(CP1254);
        assertArrayEquals(original, roundTrip(original));
        assertEquals("windows-1254, CRLF", TextFiles.decode(original).format().describe());
    }

    @Test
    void mixedLineEndingsFollowTheMajorityAndLoneCarriageReturnsBecomeNewlines() {
        assertEquals("\r\n", TextFiles.dominantSeparator("a\r\nb\r\nc\nd"));
        assertEquals("\n", TextFiles.dominantSeparator("a\r\nb\nc\nd"));
        assertEquals("\n", TextFiles.dominantSeparator("no breaks"));
        assertEquals("a\nb\nc\n", TextFiles.normalizeLineEndings("a\rb\r\nc\n"));
    }

    @Test
    void binaryFilesAreRefusedWithAClearMessage() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
        TextFiles.NotTextException e = assertThrows(TextFiles.NotTextException.class, () -> TextFiles.decode(png));
        assertEquals(e.getMessage(), TextFiles.describe(e));
        assertTrue(e.getMessage().contains("not look like a text file"));
    }

    @Test
    void bytesInvalidInUtf8AndWindows1254StillOpenAsLatin1() throws IOException {
        // 0x81 is undefined in windows-1254 and a stray continuation byte in UTF-8.
        byte[] original = {'a', (byte) 0x81, 'b'};
        TextFiles.Decoded d = TextFiles.decode(original);
        assertEquals(StandardCharsets.ISO_8859_1, d.format().charset());
        assertEquals(3, d.text().length());
        assertArrayEquals(original, roundTrip(original));
    }

    // --- encoding ----------------------------------------------------------------------------------

    @Test
    void newDocumentsAreWrittenAsBeforeUtf8WithLf() {
        TextFiles.Encoded e = TextFiles.encode("a\nb", TextFiles.Format.DEFAULT);
        assertArrayEquals("a\nb".getBytes(StandardCharsets.UTF_8), e.bytes());
        assertFalse(e.fellBackToUtf8());
    }

    @Test
    void charactersTheOriginalCharsetCannotStoreFallBackToUtf8InsteadOfBeingLost() {
        TextFiles.Format cp = new TextFiles.Format(CP1254, false, "\r\n");
        TextFiles.Encoded e = TextFiles.encode("Güzel 🌙\nson", cp);
        assertTrue(e.fellBackToUtf8());
        assertEquals(StandardCharsets.UTF_8, e.format().charset());
        assertEquals("\r\n", e.format().lineSeparator(), "line endings are still kept");
        assertEquals("Güzel 🌙\r\nson", new String(e.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void formatRejectsUnknownLineSeparators() {
        assertThrows(IllegalArgumentException.class, () -> new TextFiles.Format(StandardCharsets.UTF_8, false, "\r"));
    }

    // --- writing -----------------------------------------------------------------------------------

    @Test
    void atomicWriteReplacesTheFileAndLeavesNoTemporaryFiles() throws IOException {
        Path target = dir.resolve("notes.txt");
        Files.writeString(target, "old contents");
        TextFiles.write(target, "new\ncontents", new TextFiles.Format(StandardCharsets.UTF_8, false, "\r\n"));

        assertEquals("new\r\ncontents", Files.readString(target));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count(), "temporary file left behind");
        }
    }

    @Test
    void writeCreatesAMissingFile() throws IOException {
        Path target = dir.resolve("fresh.md");
        TextFiles.write(target, "# Title", TextFiles.Format.DEFAULT);
        assertEquals("# Title", Files.readString(target));
    }

    @Test
    void readOnlyTargetIsRefusedAndKeptIntact() throws IOException {
        Path target = dir.resolve("locked.txt");
        Files.writeString(target, "keep me");
        assertTrue(target.toFile().setReadOnly());
        try {
            AccessDeniedException e = assertThrows(AccessDeniedException.class,
                    () -> TextFiles.write(target, "overwrite", TextFiles.Format.DEFAULT));
            assertTrue(TextFiles.describe(e).startsWith("Access denied"));
            assertEquals("keep me", Files.readString(target));
        } finally {
            assertTrue(target.toFile().setWritable(true));
        }
    }

    @Test
    void writingIntoAMissingFolderFailsWithoutSideEffects() {
        Path target = dir.resolve("missing").resolve("x.txt");
        assertThrows(IOException.class, () -> TextFiles.write(target, "x", TextFiles.Format.DEFAULT));
        assertFalse(Files.exists(dir.resolve("missing")));
    }

    @Test
    void ioErrorsAreDescribedForHumans() {
        assertEquals("The file or its folder no longer exists.",
                TextFiles.describe(new NoSuchFileException("C:\\gone.txt")));
        assertEquals("disk full", TextFiles.describe(new IOException("disk full")));
        assertEquals("IOException", TextFiles.describe(new IOException()));
    }

    @Test
    void readFromDiskMatchesDecode() throws IOException {
        Path target = dir.resolve("tr.txt");
        Files.write(target, (TURKISH + "\r\n").getBytes(CP1254));
        TextFiles.Decoded d = TextFiles.read(target);
        assertEquals(TURKISH + "\n", d.text());
        assertEquals("windows-1254, CRLF", d.format().describe());
    }
}
