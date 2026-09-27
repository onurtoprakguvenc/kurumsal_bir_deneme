package org.example.index;

import org.example.TestDocs;
import org.example.core.SearchIndex;
import org.example.model.DocumentRecord;
import org.example.model.Location;
import org.example.model.SearchHit;
import org.example.model.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ranking, query syntax, filters, removal, compaction and the vocabulary statistics of the in-memory index. */
class InvertedIndexTest {

    static DocumentRecord doc(String name, String... chunkTexts) {
        return TestDocs.doc(name, chunkTexts);
    }

    @Test
    void foldsTurkishAndAccentsAndRanksBm25() {
        InvertedIndex index = new InvertedIndex();
        DocumentRecord a = doc("a.txt", "ŞİRKET kira sözleşmesi. Kira bedeli her yıl artar.");
        DocumentRecord b = doc("b.txt", "Bu belge tamamen başka bir konudan bahseder: bütçe.");
        assertTrue(index.add(a));
        assertTrue(index.add(b));
        assertFalse(index.add(a), "same SHA-256 is a duplicate");

        SearchResult r = index.search("sirket KIRA", 10);
        assertEquals(1, r.hits().size());
        assertEquals(a.sha256(), r.hits().getFirst().docId());
        assertEquals(List.of("sirket", "kira"), r.terms());
        assertTrue(r.hits().getFirst().matches().size() >= 3, "all occurrences are located");
    }

    @Test
    void phrasePrefixExclusionAndSuffixedForms() {
        InvertedIndex index = new InvertedIndex();
        DocumentRecord contract = doc("c.txt", "kira bedeli artışı yıllık yüzde on", "kiracının yükümlülükleri");
        DocumentRecord other = doc("o.txt", "bedeli kira ile ilgili değil ama kiralık araç");
        index.add(contract);
        index.add(other);

        SearchResult phrase = index.search("\"kira bedeli\"", 10);
        assertEquals(1, phrase.hits().size(), "only the exact word order matches");
        assertEquals(contract.sha256(), phrase.hits().getFirst().docId());

        SearchResult prefix = index.search("kirac*", 10);
        assertEquals(1, prefix.hits().size());
        assertEquals(1, prefix.hits().getFirst().chunkIndex());

        // "kira" (4+ letters) also reaches suffixed forms at reduced weight
        SearchResult implicit = index.search("kira", 10);
        assertEquals(3, implicit.hits().size());

        SearchResult excluded = index.search("kira -kiralik", 10);
        assertTrue(excluded.hits().stream().noneMatch(h -> h.docId().equals(other.sha256())));
    }

    @Test
    void filtersRestrictBeforeRanking() {
        InvertedIndex index = new InvertedIndex();
        DocumentRecord a = doc("a.txt", "rapor rapor rapor bütçe");
        DocumentRecord b = doc("b.txt", "rapor özeti");
        index.add(a);
        index.add(b);
        SearchResult onlyB = index.search("rapor", 10, SearchIndex.Filter.NONE.withDocuments(b.sha256()::equals));
        assertEquals(1, onlyB.hits().size());
        assertEquals(b.sha256(), onlyB.hits().getFirst().docId());

        SearchIndex.Filter near = new SearchIndex.Filter(null, List.of(),
                List.of(new SearchIndex.Proximity("rapor", "butce", 1)));
        SearchResult close = index.search("rapor butce", 10, near);
        assertEquals(1, close.hits().size());
        assertEquals(a.sha256(), close.hits().getFirst().docId());
    }

    @Test
    void removeHidesDocumentAndReleasesStats() {
        InvertedIndex index = new InvertedIndex();
        DocumentRecord a = doc("a.txt", "alfa beta", "gama");
        DocumentRecord b = doc("b.txt", "alfa delta");
        index.add(a);
        index.add(b);
        assertTrue(index.remove(a.sha256()));
        assertFalse(index.remove(a.sha256()));
        assertFalse(index.contains(a.sha256()));
        SearchResult r = index.search("alfa gama", 10);
        assertEquals(1, r.hits().size());
        assertEquals(b.sha256(), r.hits().getFirst().docId());
        SearchIndex.Stats s = index.stats();
        assertEquals(1, s.documents());
        assertEquals(1, s.chunks());
        assertTrue(index.lookup("gama").isEmpty());
    }

    /** Builds enough dead chunks to trigger compaction, then compares against an index of the survivors only. */
    @Test
    void compactionGivesExactlyTheSameResultsAsAFreshIndex() {
        Random random = new Random(42);
        String[] words = {"kira", "kiracı", "bedel", "sözleşme", "artış", "yıllık", "fatura", "rapor", "bütçe",
                "tahsilat", "gider", "gelir", "vergi", "kdv", "stok", "depo", "müşteri", "tedarik", "sipariş", "iade"};
        List<DocumentRecord> all = new ArrayList<>();
        for (int d = 0; d < 300; d++) {
            String[] chunks = new String[12];
            for (int c = 0; c < chunks.length; c++) {
                StringBuilder sb = new StringBuilder();
                for (int w = 0; w < 40; w++) {
                    sb.append(words[random.nextInt(words.length)]).append(w % 9 == 8 ? ". " : " ");
                }
                chunks[c] = sb.toString();
            }
            all.add(doc("doc" + d + ".txt", chunks));
        }
        InvertedIndex compacted = new InvertedIndex();
        all.forEach(compacted::add);
        List<DocumentRecord> survivors = new ArrayList<>();
        for (int d = 0; d < all.size(); d++) {
            if (d % 5 == 0) {
                survivors.add(all.get(d));
            } else {
                compacted.remove(all.get(d).sha256()); // 240 × 12 = 2 880 dead chunks > 720 live: compacts
            }
        }
        InvertedIndex fresh = new InvertedIndex();
        survivors.forEach(fresh::add);

        assertEquals(fresh.stats(), compacted.stats(), "counts, dictionary and postings agree after compaction");
        for (String query : List.of("kira", "kiracı bedel", "\"yıllık fatura\"", "vergi -kdv", "sip*", "gelir gider")) {
            assertSameHits(fresh.search(query, 50), compacted.search(query, 50), query);
        }
        assertEquals(fresh.lookup("rapor"), compacted.lookup("rapor"));
        DocumentRecord probe = survivors.get(7);
        assertEquals(fresh.chunkText(probe.sha256(), 3), compacted.chunkText(probe.sha256(), 3));
        assertEquals(fresh.characteristicTerms(probe.sha256(), 5), compacted.characteristicTerms(probe.sha256(), 5));

        // Still writable after compaction: a new document lands after the remapped ordinals.
        DocumentRecord late = doc("late.txt", "kira kira kira benzersizkelime");
        assertTrue(compacted.add(late));
        assertTrue(fresh.add(late));
        assertSameHits(fresh.search("benzersizkelime kira", 20), compacted.search("benzersizkelime kira", 20), "late");
    }

    private static void assertSameHits(SearchResult expected, SearchResult actual, String query) {
        assertEquals(expected.matchedChunks(), actual.matchedChunks(), query);
        assertEquals(expected.hits().size(), actual.hits().size(), query);
        for (int i = 0; i < expected.hits().size(); i++) {
            SearchHit e = expected.hits().get(i);
            SearchHit a = actual.hits().get(i);
            assertEquals(e.docId(), a.docId(), query + " #" + i);
            assertEquals(e.chunkIndex(), a.chunkIndex(), query + " #" + i);
            assertEquals(e.score(), a.score(), 1e-9, query + " #" + i);
            assertEquals(e.snippet(), a.snippet(), query + " #" + i);
            List<Location> em = e.matches();
            assertEquals(em, a.matches(), query + " #" + i);
        }
    }

    @Test
    void vocabularyListsFormsByPrefixMostWidespreadFirst() {
        InvertedIndex index = new InvertedIndex();
        DocumentRecord a = doc("a.txt", "kira kira kiracı", "kiranın süresi");
        DocumentRecord b = doc("b.txt", "kira artışı vergi");
        index.add(a);
        index.add(b);

        List<SearchIndex.TermStat> kira = index.vocabulary("KİRA*", 10, null);
        assertEquals(List.of("kira", "kiraci", "kiranin"), kira.stream().map(SearchIndex.TermStat::term).toList());
        SearchIndex.TermStat first = kira.getFirst();
        assertEquals(2, first.passages());
        assertEquals(2, first.documents());
        assertEquals(3, first.occurrences());

        // A stop word or a single letter is still a valid prefix.
        assertEquals(List.of("vergi"), index.vocabulary("ve", 10, null).stream().map(SearchIndex.TermStat::term).toList());
        assertFalse(index.vocabulary("k", 10, null).isEmpty());

        // Scope and removal are honoured.
        assertEquals(1, index.vocabulary("kira", 10, b.sha256()::equals).size());
        index.remove(a.sha256());
        assertEquals(List.of("kira"), index.vocabulary("kira", 10, null).stream().map(SearchIndex.TermStat::term).toList());
        assertTrue(index.vocabulary("", 10, null).isEmpty());
        assertTrue(index.vocabulary("***", 10, null).isEmpty());
    }

    @Test
    void termDocumentsCountsPerDocument() {
        InvertedIndex index = new InvertedIndex();
        DocumentRecord a = doc("a.txt", "kira kira kiracı", "kira");
        DocumentRecord b = doc("b.txt", "kira artışı");
        index.add(a);
        index.add(b);

        List<SearchIndex.DocumentCount> exact = index.termDocuments("Kira", false, null);
        assertEquals(2, exact.size());
        assertEquals(a.sha256(), exact.getFirst().docId());
        assertEquals(3, exact.getFirst().occurrences());
        assertEquals(2, exact.getFirst().passages());

        List<SearchIndex.DocumentCount> prefix = index.termDocuments("kira*", true, null);
        assertEquals(4, prefix.getFirst().occurrences(), "kira ×3 + kiracı");
        assertEquals(2, prefix.getFirst().passages(), "a passage is counted once even with several forms in it");

        assertEquals(1, index.termDocuments("kira", false, b.sha256()::equals).size());
        assertTrue(index.termDocuments("yok", false, null).isEmpty());
        assertTrue(index.termDocuments("ve", false, null).isEmpty(), "stop words are not indexed");
    }

    @Test
    void concurrentSearchesDuringWritesStayConsistent() throws Exception {
        InvertedIndex index = new InvertedIndex();
        List<DocumentRecord> docs = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            docs.add(doc("d" + i + ".txt", "ortak kelime " + i, "ikinci parça ortak " + (i % 7)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<?> writer = pool.submit(() -> {
                for (DocumentRecord d : docs) {
                    index.add(d);
                }
                for (int i = 0; i < docs.size(); i += 2) {
                    index.remove(docs.get(i).sha256());
                }
            });
            List<Future<?>> readers = new ArrayList<>();
            for (int r = 0; r < 3; r++) {
                readers.add(pool.submit(() -> {
                    while (!writer.isDone()) {
                        SearchResult res = index.search("ortak", 20);
                        for (SearchHit h : res.hits()) {
                            // Every hit must point at text the index can still serve.
                            index.chunkText(h.docId(), h.chunkIndex());
                        }
                    }
                }));
            }
            writer.get(30, TimeUnit.SECONDS);
            for (Future<?> f : readers) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(200, index.stats().documents());
        assertEquals(200, index.termDocuments("ortak", false, null).size());
    }
}
