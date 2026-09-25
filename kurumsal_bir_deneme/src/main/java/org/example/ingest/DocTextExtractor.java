package org.example.ingest;

import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;
import org.example.ingest.PptTextExtractor.CompoundFile;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Word 97-2003 ({@code .doc}) text extraction without third-party libraries, on the same OLE2 container reader as
 * {@link PptTextExtractor}.
 *
 * <p>The {@code WordDocument} stream starts with the File Information Block (FIB); it locates the piece table
 * ({@code Clx}) in the {@code 0Table}/{@code 1Table} stream. Each piece maps a range of character positions to bytes
 * in {@code WordDocument}, stored either "compressed" (one byte per character, Windows-1252) or as UTF-16LE — Turkish
 * letters such as ş, ğ and ı always force UTF-16. Pieces are read in small blocks straight into the chunker, so
 * memory does not depend on the document size.</p>
 *
 * <p>Extracted: the main text, then footnotes and endnotes. Paragraph marks become line breaks, table cells are
 * joined with {@code " | "} and rows end a line; field codes ({@code HYPERLINK …}, {@code PAGE}) are dropped while
 * their displayed results are kept; pictures and other object anchors are skipped. Word 6/95 files and
 * password-protected documents are refused with a clear reason.</p>
 */
final class DocTextExtractor {

    private static final int WORD_IDENT = 0xA5EC;
    /** nFib of Word 97; earlier (Word 6/95) files use a different layout. */
    private static final int NFIB_WORD97 = 0x00C1;
    private static final int FLAG_ENCRYPTED = 0x0100;
    private static final int FLAG_TABLE1 = 0x0200;
    private static final int FIB_READ_BYTES = 4_096;
    private static final long MAX_CLX_BYTES = 16L << 20;
    private static final int BLOCK_BYTES = 32 * 1024;
    private static final Charset CP1252 = Charset.forName("windows-1252");

    private DocTextExtractor() {
    }

    static void extract(Path file, TextChunker sink) throws IngestionException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            CompoundFile cfb = CompoundFile.open(channel);
            CompoundFile.Entry wordEntry = cfb.find("WordDocument");
            if (wordEntry == null) {
                if (cfb.find("EncryptedPackage") != null) {
                    throw new IngestionException(Reason.ENCRYPTED, "Word document is password protected");
                }
                throw new IngestionException(Reason.UNSUPPORTED, "OLE2 file is not a Word 97-2003 document");
            }
            CompoundFile.Stream word = cfb.stream(wordEntry);
            byte[] fib = new byte[(int) Math.min(FIB_READ_BYTES, word.size())];
            word.read(0, fib, 0, fib.length);
            if (fib.length < 154 || u16(fib, 0) != WORD_IDENT) {
                throw new IngestionException(Reason.CORRUPTED, "not a Word binary document (bad FIB)");
            }
            if (u16(fib, 2) < NFIB_WORD97) {
                throw new IngestionException(Reason.UNSUPPORTED,
                        "Word 6.0/95 documents are not supported; open and save the file as .docx");
            }
            int flags = u16(fib, 0x0A);
            if ((flags & FLAG_ENCRYPTED) != 0) {
                throw new IngestionException(Reason.ENCRYPTED, "Word document is password protected");
            }

            // FibBase (32 bytes) | csw | fibRgW | cslw | fibRgLw | cbRgFcLcb | fibRgFcLcb
            int csw = u16(fib, 32);
            int lwCount = 34 + csw * 2;
            int lw = lwCount + 2;
            int cslw = u16(fib, lwCount);
            int fcCount = lw + cslw * 4;
            int fc = fcCount + 2;
            if (cslw < 11 || fc + 68 * 4 > fib.length || u16(fib, fcCount) < 34) {
                throw new IngestionException(Reason.CORRUPTED, "Word FIB is truncated");
            }
            long ccpText = u32(fib, lw + 3 * 4);
            long ccpFtn = u32(fib, lw + 4 * 4);
            long ccpHdd = u32(fib, lw + 5 * 4);
            long ccpAtn = u32(fib, lw + 7 * 4);
            long ccpEdn = u32(fib, lw + 8 * 4);
            long fcClx = u32(fib, fc + 66 * 4);
            long lcbClx = u32(fib, fc + 67 * 4);

            CompoundFile.Entry tableEntry = cfb.find((flags & FLAG_TABLE1) != 0 ? "1Table" : "0Table");
            if (tableEntry == null) {
                throw new IngestionException(Reason.CORRUPTED, "Word table stream is missing");
            }
            CompoundFile.Stream table = cfb.stream(tableEntry);
            if (lcbClx <= 0 || fcClx + lcbClx > table.size()) {
                throw new IngestionException(Reason.CORRUPTED, "Word piece table is missing");
            }
            if (lcbClx > MAX_CLX_BYTES) {
                throw new IngestionException(Reason.TOO_LARGE, "Word piece table is implausibly large");
            }
            byte[] clx = new byte[(int) lcbClx];
            table.read(fcClx, clx, 0, clx.length);
            Pieces pieces = Pieces.parse(clx);

            TextWriter writer = new TextWriter(sink);
            pieces.copy(word, 0, ccpText, writer);
            long notes = ccpText;
            if (ccpFtn > 0 && !sink.truncated()) {
                writer.separate();
                pieces.copy(word, notes, notes + ccpFtn, writer);
            }
            long endnotes = ccpText + ccpFtn + ccpHdd + ccpAtn;
            if (ccpEdn > 0 && !sink.truncated()) {
                writer.separate();
                pieces.copy(word, endnotes, endnotes + ccpEdn, writer);
            }
        } catch (IOException e) {
            throw new IngestionException(Reason.CORRUPTED, "Unreadable Word document: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IngestionException(Reason.CORRUPTED, "Malformed Word document: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    /** The piece table: character position ranges and where their bytes are in {@code WordDocument}. */
    private record Pieces(long[] cps, long[] fcs, boolean[] compressed) {

        static Pieces parse(byte[] clx) throws IngestionException {
            int pos = 0;
            while (pos < clx.length && clx[pos] == 0x01) { // Prc: property modifiers, not needed for text
                pos += 3 + u16(clx, pos + 1);
            }
            if (pos + 5 > clx.length || clx[pos] != 0x02) {
                throw new IngestionException(Reason.CORRUPTED, "Word piece table has no Pcdt");
            }
            long lcb = u32(clx, pos + 1);
            pos += 5;
            if (lcb < 4 || pos + lcb > clx.length || (lcb - 4) % 12 != 0) {
                throw new IngestionException(Reason.CORRUPTED, "Word piece table has an invalid size");
            }
            int n = (int) ((lcb - 4) / 12);
            long[] cps = new long[n + 1];
            for (int i = 0; i <= n; i++) {
                cps[i] = u32(clx, pos + i * 4);
            }
            int pcd = pos + (n + 1) * 4;
            long[] fcs = new long[n];
            boolean[] compressed = new boolean[n];
            for (int i = 0; i < n; i++) {
                long raw = u32(clx, pcd + i * 8 + 2);
                compressed[i] = (raw & 0x40000000L) != 0;
                long offset = raw & 0x3FFFFFFFL;
                fcs[i] = compressed[i] ? offset / 2 : offset;
            }
            return new Pieces(cps, fcs, compressed);
        }

        /** Streams the text of character positions {@code [from, to)} into {@code writer}. */
        void copy(CompoundFile.Stream word, long from, long to, TextWriter writer) throws IOException,
                IngestionException {
            byte[] block = new byte[BLOCK_BYTES];
            for (int i = 0; i < fcs.length && !writer.full(); i++) {
                long start = Math.max(from, cps[i]);
                long end = Math.min(to, cps[i + 1]);
                if (start >= end) {
                    continue;
                }
                int width = compressed[i] ? 1 : 2;
                long bytePos = fcs[i] + (start - cps[i]) * width;
                long remaining = (end - start) * width;
                if (bytePos < 0 || bytePos + remaining > word.size()) {
                    throw new IngestionException(Reason.CORRUPTED, "Word piece points outside the document");
                }
                while (remaining > 0 && !writer.full()) {
                    int n = (int) Math.min(block.length, remaining);
                    word.read(bytePos, block, 0, n);
                    writer.write(compressed[i] ? new String(block, 0, n, CP1252)
                            : new String(block, 0, n, StandardCharsets.UTF_16LE));
                    bytePos += n;
                    remaining -= n;
                }
            }
        }
    }

    /** Maps Word's special characters to plain text and drops field instructions. */
    private static final class TextWriter {
        private final TextChunker sink;
        /** Open fields whose instruction part (before the 0x14 separator) is still being read. */
        private int instructionDepth;
        /** Per open field: true once its separator was seen (the result part is shown). */
        private final java.util.ArrayDeque<Boolean> fields = new java.util.ArrayDeque<>();
        private boolean afterCellMark;

        TextWriter(TextChunker sink) {
            this.sink = sink;
        }

        boolean full() {
            return sink.truncated();
        }

        void separate() {
            fields.clear();
            instructionDepth = 0;
            afterCellMark = false;
            sink.append("\n\n");
        }

        void write(String text) {
            for (int i = 0; i < text.length() && !sink.truncated(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case 0x13 -> { // field begin: its instruction follows
                        fields.push(Boolean.FALSE);
                        instructionDepth++;
                    }
                    case 0x14 -> { // field separator: the displayed result follows
                        if (!fields.isEmpty() && !fields.peek()) {
                            fields.pop();
                            fields.push(Boolean.TRUE);
                            instructionDepth--;
                        }
                    }
                    case 0x15 -> { // field end
                        if (!fields.isEmpty() && !fields.pop()) {
                            instructionDepth--;
                        }
                    }
                    default -> {
                        if (instructionDepth == 0) {
                            emit(c);
                        }
                    }
                }
            }
        }

        private void emit(char c) {
            if (c == 0x07) {
                // A cell mark; a second one right after it closes the table row.
                sink.append(afterCellMark ? "\n" : " | ");
                afterCellMark = !afterCellMark;
                return;
            }
            afterCellMark = false;
            switch (c) {
                case 0x0D, 0x0B, 0x0C, 0x0E -> sink.append('\n'); // paragraph, line, page/section, column break
                case 0x09 -> sink.append('\t');
                case 0x1E -> sink.append('-');                    // non-breaking hyphen
                case 0xA0 -> sink.append(' ');
                default -> {
                    if (c >= 0x20 && c != 0x1F) {                  // drop anchors (0x01-0x08) and soft hyphens
                        sink.append(c);
                    }
                }
            }
        }
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] b, int off) {
        return Integer.toUnsignedLong((b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16
                | (b[off + 3] & 0xFF) << 24);
    }
}
