package org.example.core;

import org.example.model.DocumentRecord;
import org.example.model.DocumentType;
import org.example.model.TextChunk;
import org.example.util.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * The built-in demo report behind the command guide's 30-second {@code find} tour. It is indexed into every
 * workbench as a read-only {@link DocumentRecord#SYSTEM} document, so the tour's query always has its match no
 * matter what the workspace holds, while lists, counts and snapshots never show it.
 */
public final class SystemFixture {

    /** File name of the demo report. */
    public static final String FILE_NAME = "mustafa_bey_haziran_2025_raporu.txt";

    /** Content of the demo report. */
    public static final String TEXT = """
            MÜŞTERİ RAPORU: Mustafa Bey Haziran 2025 verileri
            - Toplam Ciro: 420.000 TL
            - Büyüme Oranı: %18
            - Durum: Onaylandı ve arşive kaldırıldı.
            """;

    private SystemFixture() {
    }

    /**
     * Writes the report to {@code dir} (restoring it if it is missing or was altered) and indexes it as a system
     * document. The record is built directly from the embedded text, so no parser runs and startup stays parse-free.
     * Protection is enforced by the application (untrack and purge refuse system documents), not by a read-only
     * file attribute, which would block deleting the project folder on Windows.
     *
     * @return {@code true} when the fixture was added, {@code false} when identical content was already indexed
     */
    public static boolean install(Workbench workbench, Path dir) throws IOException {
        Path file = dir.resolve(FILE_NAME);
        byte[] bytes = TEXT.getBytes(StandardCharsets.UTF_8);
        if (!Files.isRegularFile(file) || !Arrays.equals(Files.readAllBytes(file), bytes)) {
            Files.createDirectories(dir);
            Files.write(file, bytes);
        }
        DocumentRecord record = new DocumentRecord(Hashing.hex(Hashing.sha256().digest(bytes)), FILE_NAME, file,
                DocumentType.TXT, bytes.length, -1, 0, TEXT.length(), Instant.now(), DocumentRecord.SYSTEM,
                List.of(new TextChunk(0, -1, 0, TEXT)));
        return workbench.index().add(record);
    }
}
