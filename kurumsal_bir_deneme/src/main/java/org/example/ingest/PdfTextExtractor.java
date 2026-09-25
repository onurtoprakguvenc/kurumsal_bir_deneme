package org.example.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;

import java.io.IOException;
import java.io.Writer;
import java.nio.CharBuffer;
import java.nio.file.Path;

/**
 * PDF extraction with PDFBox 3, tuned for low heap use: the file is read through PDFBox's buffered random-access
 * reader (not loaded into a byte array), decoded streams are cached in temp files instead of RAM, and the text
 * stripper writes straight into the chunker page by page. No OCR is performed; pages without a text layer are
 * counted so callers can report scanned documents.
 */
final class PdfTextExtractor {

    record Info(int pageCount, int emptyPages) {
    }

    private PdfTextExtractor() {
    }

    static Info extract(Path file, TextChunker sink) throws IngestionException {
        return extract(file, sink, Integer.MAX_VALUE);
    }

    /** Extracts pages {@code 1..lastPage} only; previews use this so a long PDF is not stripped to the end. */
    static Info extract(Path file, TextChunker sink, int lastPage) throws IngestionException {
        try (PDDocument document = Loader.loadPDF(file.toFile(), "", IOUtils.createTempFileOnlyStreamCache())) {
            if (document.isEncrypted()) {
                // Opened with the empty user password: only owner restrictions apply, which do not stop reading.
                document.setAllSecurityToBeRemoved(true);
            }
            PageAwareStripper stripper = new PageAwareStripper(sink);
            stripper.setSortByPosition(false);
            stripper.setSuppressDuplicateOverlappingText(true);
            stripper.setLineSeparator("\n");
            stripper.setEndPage(Math.max(1, lastPage));
            try {
                stripper.writeText(document, stripper.writer);
            } catch (StopStripping stop) {
                // the chunker is full; the remaining pages would only be decoded and discarded
            }
            return new Info(document.getNumberOfPages(), stripper.emptyPages);
        } catch (InvalidPasswordException e) {
            throw new IngestionException(Reason.ENCRYPTED, "PDF is password protected", e);
        } catch (IOException e) {
            throw new IngestionException(Reason.CORRUPTED, "Unreadable PDF: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IngestionException(Reason.CORRUPTED,
                    "Malformed PDF structure: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (StackOverflowError e) {
            throw new IngestionException(Reason.CORRUPTED, "PDF object graph is too deeply nested");
        }
    }

    /** Unwinds PDFBox's page loop once the chunker accepts no more text. */
    private static final class StopStripping extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StopStripping() {
            super(null, null, false, false);
        }
    }

    private static final class PageAwareStripper extends PDFTextStripper {

        private final TextChunker sink;
        private final Writer writer;
        private long written;
        private long writtenAtPageStart;
        private int emptyPages;

        PageAwareStripper(TextChunker sink) {
            this.sink = sink;
            this.writer = new Writer() {
                @Override
                public void write(char[] buffer, int offset, int length) {
                    countVisible(buffer, offset, length);
                    sink.append(CharBuffer.wrap(buffer, offset, length));
                }

                @Override
                public void write(String text) {
                    countVisible(text);
                    sink.append(text);
                }

                @Override
                public void write(String text, int offset, int length) {
                    write(text.substring(offset, offset + length));
                }

                @Override
                public void flush() {
                    // unbuffered
                }

                @Override
                public void close() {
                    // the chunker outlives the stripper
                }
            };
        }

        private void countVisible(char[] buffer, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                if (!Character.isWhitespace(buffer[i])) {
                    written++;
                }
            }
        }

        private void countVisible(CharSequence text) {
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isWhitespace(text.charAt(i))) {
                    written++;
                }
            }
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            if (sink.truncated()) {
                throw new StopStripping();
            }
            sink.startPage(getCurrentPageNo());
            writtenAtPageStart = written;
            super.startPage(page);
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            super.endPage(page);
            if (written == writtenAtPageStart) {
                emptyPages++;
            }
        }
    }
}
