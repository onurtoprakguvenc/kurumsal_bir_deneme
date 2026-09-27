package org.example.workbench;

import org.example.core.Workbench;
import org.example.index.InvertedIndex;
import org.example.ingest.DocumentParser;
import org.example.platform.OsShellBridge;
import org.example.repl.InternalTerminalEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code terms} and {@code freq} terminal commands on a real (headless) controller. */
class VocabularyCommandsTest {

    @TempDir
    Path dir;

    private WorkbenchController c;

    @BeforeEach
    void setUp() throws IOException {
        Path docs = Files.createDirectories(dir.resolve("belgeler"));
        Files.writeString(docs.resolve("kira.txt"), "Kira sözleşmesi. Kiracı kira bedelini öder. Kiranın artışı yıllıktır.");
        Files.writeString(docs.resolve("fatura.txt"), "Fatura: kira bedeli dahil değildir. Vergi ayrıca ödenir.");
        c = new WorkbenchController(new Workbench(new DocumentParser(), new InvertedIndex(), null),
                WorkbenchController.Config.defaults(dir.resolve("data")),
                new OsShellBridge(OsShellBridge.Platform.WINDOWS, command -> {
                    throw new IOException("no processes in tests");
                }, false));
        c.addPath(docs, InternalTerminalEngine.Output.NONE);
    }

    @AfterEach
    void tearDown() {
        c.close();
    }

    private List<String> run(String line) {
        long from = c.terminal().buffer().nextSequence();
        InternalTerminalEngine.Outcome outcome = c.terminal().execute(line);
        List<String> all = c.terminal().buffer().snapshot();
        int fresh = (int) Math.min(all.size(), c.terminal().buffer().nextSequence() - from);
        List<String> lines = all.subList(all.size() - fresh, all.size());
        assertInstanceOf(InternalTerminalEngine.Outcome.Completed.class, outcome, String.join("\n", lines));
        return lines;
    }

    private static boolean has(List<String> lines, String... parts) {
        return lines.stream().anyMatch(l -> {
            for (String p : parts) {
                if (!l.contains(p)) {
                    return false;
                }
            }
            return true;
        });
    }

    @Test
    void termsListsSuffixedFormsWithCounts() {
        List<String> out = run("terms kira");
        assertTrue(has(out, "kira ") && has(out, "kiraci") && has(out, "kiranin"), String.join("\n", out));
        assertTrue(has(out, "find kira*"), "points at the prefix search: " + out);
        assertTrue(has(run("terms zzz"), "no indexed word"), "empty result is explained");
    }

    @Test
    void termsHonoursScopeFlags() {
        List<String> out = run("terms ver --in fatura.txt");
        assertTrue(has(out, "vergi"), String.join("\n", out));
        List<String> none = run("terms kiraci --type pdf");
        assertTrue(has(none, "no indexed word"), String.join("\n", none));
    }

    @Test
    void freqCountsPerDocumentMostFirst() {
        List<String> out = run("freq kira");
        assertTrue(has(out, "'kira' occurs 3 time(s) in 2 document(s)"), String.join("\n", out));
        int kira = indexOf(out, "kira.txt");
        int fatura = indexOf(out, "fatura.txt");
        assertTrue(kira >= 0 && fatura > kira, "kira.txt (2×) before fatura.txt (1×): " + out);

        assertTrue(has(run("freq kira*"), "in 2 document(s)"));
        assertTrue(has(run("freq yok"), "does not occur"));
    }

    @Test
    void usageErrorsFailCleanly() {
        assertInstanceOf(InternalTerminalEngine.Outcome.Failed.class, c.terminal().execute("terms"));
        assertInstanceOf(InternalTerminalEngine.Outcome.Failed.class, c.terminal().execute("freq iki kelime"));
        assertInstanceOf(InternalTerminalEngine.Outcome.Failed.class, c.terminal().execute("freq kira --top many"));
    }

    private static int indexOf(List<String> lines, String part) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(part)) {
                return i;
            }
        }
        return -1;
    }
}
