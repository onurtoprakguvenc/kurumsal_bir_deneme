package org.example.storage;

import org.example.TestDocs;
import org.example.model.DocumentRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crash safety of the binary snapshot: round trip, bit rot, torn writes, and the carry-over of unloaded blocks. */
class IndexStorageEngineTest {

    @TempDir
    Path dir;

    private static List<DocumentRecord> documents(int n, String tag) {
        List<DocumentRecord> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(TestDocs.doc(tag + i + ".txt", "birinci parça " + tag + i, "ikinci parça ünlü ğüşiöç"));
        }
        return out;
    }

    private static List<String> ids(List<IndexStorageEngine.Entry> entries) {
        return entries.stream().map(e -> e.document().sha256()).toList();
    }

    @Test
    void roundTripRestoresEveryDocumentAndChunk() throws IOException {
        IndexStorageEngine engine = new IndexStorageEngine(dir.resolve("index.dwb"));
        List<DocumentRecord> docs = documents(25, "d");
        IndexStorageEngine.SaveReport saved = engine.save(docs, d -> MetadataRecord.of(d, 1, -1));
        assertEquals(25, saved.documents());
        assertEquals(50, saved.chunks());

        IndexStorageEngine.LoadReport loaded = engine.load();
        assertEquals(IndexStorageEngine.Origin.PRIMARY, loaded.origin());
        assertEquals(docs.stream().map(DocumentRecord::sha256).toList(), ids(loaded.entries()));
        DocumentRecord first = loaded.entries().getFirst().document();
        assertEquals(docs.getFirst().chunks(), first.chunks());
        assertEquals(docs.getFirst().fileName(), first.fileName());
        assertTrue(loaded.problems().isEmpty());
    }

    @Test
    void firstStartHasNothingToLoad() throws IOException {
        IndexStorageEngine engine = new IndexStorageEngine(dir.resolve("index.dwb"));
        assertFalse(engine.exists());
        IndexStorageEngine.LoadReport r = engine.load();
        assertEquals(IndexStorageEngine.Origin.NONE, r.origin());
        assertTrue(r.entries().isEmpty());
    }

    @Test
    void bitRotInThePrimaryFallsBackToTheBackupAsAWhole() throws IOException {
        IndexStorageEngine engine = new IndexStorageEngine(dir.resolve("index.dwb"));
        List<DocumentRecord> v1 = documents(5, "old");
        List<DocumentRecord> v2 = documents(6, "new");
        engine.save(v1, d -> MetadataRecord.of(d, 1, -1));
        engine.save(v2, d -> MetadataRecord.of(d, 1, -1)); // v1 becomes the backup
        try (RandomAccessFile raf = new RandomAccessFile(engine.file().toFile(), "rw")) {
            raf.seek(raf.length() / 2);
            int b = raf.read();
            raf.seek(raf.length() / 2);
            raf.write(b ^ 0x40);
        }
        IndexStorageEngine.LoadReport r = engine.load();
        assertEquals(IndexStorageEngine.Origin.BACKUP, r.origin());
        assertEquals(v1.stream().map(DocumentRecord::sha256).toList(), ids(r.entries()), "never a half-loaded mix");
        assertEquals(1, r.problems().size());

        List<IndexStorageEngine.FileCheck> checks = engine.verify();
        assertFalse(checks.getFirst().valid());
        assertTrue(checks.get(2).valid());
    }

    @Test
    void truncatedPrimaryAndCrashBetweenRenamesAreRecovered() throws IOException {
        Path file = dir.resolve("index.dwb");
        IndexStorageEngine engine = new IndexStorageEngine(file);
        List<DocumentRecord> docs = documents(4, "t");
        engine.save(docs, d -> MetadataRecord.of(d, 1, -1));

        // Crash after "primary → backup" but before "temp → primary": only the completed temp is left.
        Files.move(file, dir.resolve("index.dwb.tmp"));
        IndexStorageEngine.LoadReport fromTemp = engine.load();
        assertEquals(IndexStorageEngine.Origin.RECOVERED_TEMP, fromTemp.origin());
        assertEquals(4, fromTemp.entries().size());

        // A torn write (footer missing) is rejected instead of half-loading.
        Files.move(dir.resolve("index.dwb.tmp"), file);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 5));
        IndexStorageEngine.LoadReport torn = engine.load();
        assertEquals(IndexStorageEngine.Origin.NONE, torn.origin());
        assertEquals(1, torn.problems().size());
    }

    @Test
    void carryOverKeepsDocumentsThatAreNotInMemory() throws IOException {
        IndexStorageEngine engine = new IndexStorageEngine(dir.resolve("index.dwb"));
        List<DocumentRecord> all = documents(6, "c");
        engine.save(all, d -> MetadataRecord.of(d, 1, -1));

        // Only the first three are in memory now (e.g. the heap ceiling stopped the warm-up).
        List<DocumentRecord> inMemory = all.subList(0, 3);
        Set<String> notLoaded = Set.of(all.get(3).sha256(), all.get(5).sha256());
        IndexStorageEngine.SaveReport r = engine.save(inMemory, d -> MetadataRecord.of(d, 1, -1), notLoaded);
        assertEquals(5, r.documents());
        List<String> restored = ids(engine.load().entries());
        assertEquals(5, restored.size());
        assertTrue(restored.containsAll(notLoaded));
        assertFalse(restored.contains(all.get(4).sha256()), "only the named documents are carried over");
    }

    @Test
    void streamingLoadHonoursTheVisitorsChoice() throws IOException {
        IndexStorageEngine engine = new IndexStorageEngine(dir.resolve("index.dwb"));
        List<DocumentRecord> docs = documents(10, "s");
        engine.save(docs, d -> MetadataRecord.of(d, 1, -1));
        List<String> seen = new ArrayList<>();
        engine.load(new IndexStorageEngine.Visitor() {
            @Override
            public boolean wants(MetadataRecord header) {
                assertTrue(header.chunks().isEmpty(), "the text is not decoded before it is wanted");
                return header.fileName().compareTo("s5") < 0;
            }

            @Override
            public void entry(IndexStorageEngine.Entry entry) {
                seen.add(entry.document().fileName());
            }
        });
        assertEquals(List.of("s0.txt", "s1.txt", "s2.txt", "s3.txt", "s4.txt"), seen);
    }
}
