package org.example.ingest;

import org.example.core.DocumentIngestor.IngestResult;
import org.example.core.IngestionException;
import org.example.model.ContentKind;
import org.example.model.DocumentRecord;
import org.example.model.DocumentType;
import org.example.model.TextChunk;
import org.example.util.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ingest pipeline end to end on real files: detection, decoding, chunking, hashing and refusals. */
class DocumentParserTest {

    @TempDir
    Path dir;

    private final DocumentParser parser = new DocumentParser();

    private DocumentRecord ingest(Path file) throws IngestionException {
        IngestResult r = parser.ingest(file, null, sha -> false);
        return assertInstanceOf(IngestResult.Ingested.class, r).document();
    }

    private static String text(DocumentRecord d) {
        StringBuilder sb = new StringBuilder();
        for (TextChunk c : d.chunks()) {
            sb.append(c.text());
        }
        return sb.toString();
    }

    @Test
    void utf8TextIsHashedChunkedAndNamed() throws Exception {
        String body = "Kira sözleşmesi madde 4: artış oranı %25.\n".repeat(200);
        Path file = Files.writeString(dir.resolve("sözleşme.txt"), body);
        DocumentRecord d = ingest(file);
        assertEquals(DocumentType.TXT, d.type());
        assertEquals(Hashing.sha256Hex(file), d.sha256());
        assertEquals("sözleşme.txt", d.fileName());
        assertTrue(d.chunks().size() > 1);
        assertTrue(text(d).startsWith("Kira sözleşmesi madde 4"));
        assertEquals(d.charCount(), text(d).length());
    }

    @Test
    void knownHashIsADuplicateBeforeAnyExtraction() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "aynı içerik");
        String sha = Hashing.sha256Hex(file);
        IngestResult r = parser.ingest(file, null, sha::equals);
        assertInstanceOf(IngestResult.Duplicate.class, r);
        assertEquals(sha, r.sha256());
    }

    @Test
    void turkishAnsiFallsBackToWindows1254() throws Exception {
        Path file = dir.resolve("ansi.txt");
        Files.write(file, "Şirket ğüşıöç İĞÜŞÖÇ".getBytes(Charset.forName("windows-1254")));
        assertEquals("Şirket ğüşıöç İĞÜŞÖÇ", text(ingest(file)));
    }

    @Test
    void utf16WithAndWithoutBom() throws Exception {
        Path bom = dir.resolve("bom.txt");
        Files.write(bom, "﻿merhaba dünya".getBytes(StandardCharsets.UTF_16LE));
        assertEquals("merhaba dünya", text(ingest(bom)));
        Path noBom = dir.resolve("nobom.txt");
        Files.write(noBom, "hello world, plain ascii in utf-16".getBytes(StandardCharsets.UTF_16BE));
        assertEquals("hello world, plain ascii in utf-16", text(ingest(noBom)));
    }

    @Test
    void csvBecomesCoordinateTables() throws Exception {
        Path file = Files.writeString(dir.resolve("liste.csv"), "ad;tutar\nAli;100\nAyşe;250\n");
        DocumentRecord d = ingest(file);
        assertEquals(DocumentType.CSV, d.type());
        String t = text(d);
        assertTrue(t.contains("Ayşe") && t.contains("250"), t);
        assertTrue(t.contains("|"), "rendered as a Markdown table: " + t);
    }

    @Test
    void minimalDocxIsReadWithoutADom() throws Exception {
        Path file = dir.resolve("rapor.docx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/"
                    + "package/2006/content-types\"/>");
            put(zip, "word/document.xml", "<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas."
                    + "openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + "<w:p><w:r><w:t>Birinci paragraf</w:t></w:r></w:p>"
                    + "<w:p><w:r><w:t>Tablo:</w:t></w:r></w:p>"
                    + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc>"
                    + "<w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                    + "</w:body></w:document>");
        }
        DocumentRecord d = ingest(file);
        assertEquals(DocumentType.DOCX, d.type());
        String t = text(d);
        assertTrue(t.contains("Birinci paragraf") && t.contains("A1") && t.contains("B1"), t);
    }

    @Test
    void renamedDocxIsDetectedByContent() throws Exception {
        Path file = dir.resolve("yanlis-ad.bin.txt");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/"
                    + "wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>içerik</w:t></w:r></w:p></w:body></w:document>");
        }
        assertEquals(DocumentType.DOCX, ingest(file).type());
    }

    @Test
    void zipBombIsRefused() throws Exception {
        Path file = dir.resolve("bomba.docx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 600; i++) { // 600 MiB of zeros, a few hundred KB compressed
                zip.write(zeros);
            }
            zip.closeEntry();
        }
        IngestionException e = assertThrows(IngestionException.class, () -> parser.ingest(file, null, s -> false));
        assertTrue(e.getMessage() != null && !e.getMessage().isBlank());
    }

    @Test
    void refusalsCarryPreciseReasons() throws Exception {
        Path empty = Files.createFile(dir.resolve("bos.txt"));
        assertEquals(IngestionException.Reason.EMPTY,
                assertThrows(IngestionException.class, () -> parser.ingest(empty, null, s -> false)).reason());
        Path blank = Files.writeString(dir.resolve("bosluk.txt"), " \n\t \n");
        assertEquals(IngestionException.Reason.EMPTY,
                assertThrows(IngestionException.class, () -> parser.ingest(blank, null, s -> false)).reason());
        Path missing = dir.resolve("yok.txt");
        assertEquals(IngestionException.Reason.NOT_FOUND,
                assertThrows(IngestionException.class, () -> parser.ingest(missing, null, s -> false)).reason());
        Path fakePdf = Files.writeString(dir.resolve("sahte.pdf"), "not a pdf at all");
        assertEquals(IngestionException.Reason.CORRUPTED,
                assertThrows(IngestionException.class, () -> parser.ingest(fakePdf, null, s -> false)).reason());
        assertEquals(IngestionException.Reason.NOT_A_FILE,
                assertThrows(IngestionException.class, () -> parser.ingest(dir, null, s -> false)).reason());
    }

    @Test
    void mediaTakesTheBinaryTrackWithoutExtraction() throws Exception {
        Path video = dir.resolve("klip.bin");
        byte[] mp4 = new byte[200_000];
        System.arraycopy(new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}, 0, mp4, 0, 12);
        Files.write(video, mp4);
        assertEquals(ContentKind.BINARY, DocumentParser.classify(video, null));
        IngestResult r = parser.ingest(video, null, s -> false);
        IngestResult.Registered reg = assertInstanceOf(IngestResult.Registered.class, r);
        assertEquals(Hashing.sha256Hex(video), reg.asset().sha256());
        assertEquals(200_000, reg.asset().sizeBytes());
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        OutputStream out = zip;
        out.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
