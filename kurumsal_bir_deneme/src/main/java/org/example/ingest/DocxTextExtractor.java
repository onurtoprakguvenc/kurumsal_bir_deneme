package org.example.ingest;

import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * DOCX extraction without a document object model: the ZIP is opened with random access (central directory
 * only) and {@code word/document.xml} is pulled through a StAX cursor, so memory use is independent of the
 * document size. Footnotes and endnotes follow the body.
 *
 * <p>Handles paragraphs, tabs, breaks, tables (cells joined with {@code " | "}), hyperlinks and text boxes;
 * skips deleted revisions, field instructions and {@code mc:Fallback} duplicates. DTDs and external entities
 * are disabled; decompressed XML is capped to defeat ZIP bombs.</p>
 */
final class DocxTextExtractor {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE_DOCUMENT_REL_SUFFIX = "/officeDocument";
    private static final long MAX_XML_BYTES = 256L * 1024 * 1024;

    private DocxTextExtractor() {
    }

    static void extract(Path file, TextChunker sink) throws IngestionException {
        extract(file, sink, Limits.DEFAULTS);
    }

    /**
     * Same package guards as the other OOXML extractors: the ZIP central directory is preflighted against entry
     * count, declared sizes and inflate ratios, and every XML part is depth-limited.
     */
    static void extract(Path file, TextChunker sink, Limits limits) throws IngestionException {
        OoxmlGuards.rejectOle2(file, "DOCX", "Word (.doc)");
        OoxmlGuards.preflight(file, limits, "DOCX");
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            String mainPart = mainDocumentPart(zip);
            ZipEntry main = zip.getEntry(mainPart);
            if (main == null) {
                throw new IngestionException(Reason.CORRUPTED, "ZIP archive is not a Word document (no " + mainPart + ")");
            }
            long[] budget = {MAX_XML_BYTES};
            int maxDepth = limits.maxXmlDepth();
            streamPart(zip, main, sink, budget, maxDepth);
            String folder = mainPart.contains("/") ? mainPart.substring(0, mainPart.lastIndexOf('/') + 1) : "";
            for (String extra : new String[]{"footnotes.xml", "endnotes.xml"}) {
                ZipEntry entry = zip.getEntry(folder + extra);
                if (entry != null && !sink.truncated()) {
                    sink.append("\n\n");
                    streamPart(zip, entry, sink, budget, maxDepth);
                }
            }
        } catch (ZipException e) {
            throw new IngestionException(Reason.CORRUPTED, "Corrupted DOCX archive: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot read DOCX: " + e.getMessage(), e);
        }
    }

    private static String mainDocumentPart(ZipFile zip) throws IngestionException {
        ZipEntry rels = zip.getEntry("_rels/.rels");
        if (rels == null) {
            return "word/document.xml";
        }
        try (InputStream in = OoxmlGuards.bounded(zip.getInputStream(rels), 4L * 1024 * 1024, new long[]{4L * 1024 * 1024})) {
            XMLStreamReader r = OoxmlGuards.xmlInputFactory().createXMLStreamReader(in);
            try {
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())
                            && (REL.equals(r.getNamespaceURI()) || r.getNamespaceURI() == null)) {
                        String type = r.getAttributeValue(null, "Type");
                        String target = r.getAttributeValue(null, "Target");
                        if (type != null && type.endsWith(OFFICE_DOCUMENT_REL_SUFFIX) && target != null) {
                            String part = target.startsWith("/") ? target.substring(1) : target;
                            if (!part.contains("..")) {
                                return part;
                            }
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException | XMLStreamException e) {
            throw new IngestionException(Reason.CORRUPTED, "Unreadable package relationships: " + e.getMessage(), e);
        }
        return "word/document.xml";
    }

    private static void streamPart(ZipFile zip, ZipEntry entry, TextChunker sink, long[] budget, int maxDepth)
            throws IngestionException {
        try (InputStream in = OoxmlGuards.bounded(zip.getInputStream(entry), MAX_XML_BYTES, budget)) {
            XMLStreamReader r = OoxmlGuards.xmlInputFactory().createXMLStreamReader(in);
            try {
                walk(r, sink, maxDepth);
            } finally {
                r.close();
            }
        } catch (OoxmlGuards.BudgetExceededException e) {
            throw zipBomb(e);
        } catch (XMLStreamException e) {
            if (OoxmlGuards.budgetExceeded(e)) {
                throw zipBomb(e);
            }
            if (String.valueOf(e.getMessage()).contains("nesting exceeds")) {
                throw new IngestionException(Reason.CORRUPTED, "Document XML is nested too deeply", e);
            }
            throw new IngestionException(Reason.CORRUPTED, "Malformed DOCX XML: "
                    + String.valueOf(e.getMessage()).replace('\n', ' '), e);
        } catch (IOException e) {
            throw new IngestionException(Reason.CORRUPTED, "Cannot inflate DOCX part: " + e.getMessage(), e);
        }
    }

    private static void walk(XMLStreamReader r, TextChunker sink, int maxDepth) throws XMLStreamException {
        boolean inText = false;
        int skipDepth = 0;
        int tableDepth = 0;
        int depth = 0;
        // One cell counter per open table, so a nested table does not reset its parent row's separators.
        Deque<int[]> cellInRow = new ArrayDeque<>();
        while (r.hasNext() && !sink.truncated()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (++depth > maxDepth) {
                    throw new XMLStreamException("XML nesting exceeds " + maxDepth + " levels");
                }
                String ns = r.getNamespaceURI();
                String name = r.getLocalName();
                if (skipDepth > 0) {
                    skipDepth++;
                    continue;
                }
                if (MC.equals(ns) && "Fallback".equals(name)) {
                    skipDepth = 1;
                    continue;
                }
                if (!W.equals(ns)) {
                    continue;
                }
                switch (name) {
                    case "t" -> inText = true;
                    case "delText", "instrText" -> skipDepth = 1;
                    case "tab", "ptab" -> sink.append('\t');
                    case "br", "cr" -> sink.append(tableDepth > 0 ? ' ' : '\n');
                    case "noBreakHyphen" -> sink.append('-');
                    case "tbl" -> {
                        tableDepth++;
                        cellInRow.push(new int[1]);
                        sink.append('\n');
                    }
                    case "tr" -> {
                        if (!cellInRow.isEmpty()) {
                            cellInRow.peek()[0] = 0;
                        }
                    }
                    case "tc" -> {
                        if (!cellInRow.isEmpty() && cellInRow.peek()[0]++ > 0) {
                            sink.append(" | ");
                        }
                    }
                    default -> {
                        // formatting and structural elements carry no text
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
                if (skipDepth > 0) {
                    skipDepth--;
                    continue;
                }
                if (!W.equals(r.getNamespaceURI())) {
                    continue;
                }
                switch (r.getLocalName()) {
                    case "t" -> inText = false;
                    case "p" -> sink.append(tableDepth > 0 ? ' ' : '\n');
                    case "tr" -> sink.append('\n');
                    case "tbl" -> {
                        tableDepth = Math.max(0, tableDepth - 1);
                        if (!cellInRow.isEmpty()) {
                            cellInRow.pop();
                        }
                        sink.append('\n');
                    }
                    default -> {
                        // nothing to emit
                    }
                }
            } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                    || event == XMLStreamConstants.SPACE) && inText && skipDepth == 0) {
                sink.append(CharBuffer.wrap(r.getTextCharacters(), r.getTextStart(), r.getTextLength()));
            }
        }
    }

    private static IngestionException zipBomb(Exception cause) {
        return new IngestionException(Reason.TOO_LARGE, "Decompressed DOCX content exceeds "
                + (MAX_XML_BYTES >> 20) + " MB (possible ZIP bomb)", cause);
    }
}
