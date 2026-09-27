package org.example.admin;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only, structured plain-text audit trail ({@code audit.log}).
 *
 * <pre>
 * 2026-09-21T08:14:03.512Z WARN     INGEST_FAILURE file="C:\Belgeler\rapor.pdf" reason=CORRUPTED msg="Unreadable PDF"
 * 2026-09-21T08:14:09.020Z INFO     FILE_ACCESS    action=OPEN file="C:\Belgeler\kira.txt" result=Launched
 * 2026-09-21T08:15:40.771Z SECURITY AUTH           op="limit --memory" result=denied
 * </pre>
 *
 * <p>One line per event: ISO-8601 UTC timestamp, level, category, then {@code key=value} fields. Values are quoted
 * when needed and CR/LF are escaped, so a crafted file name cannot forge extra log lines. Each entry is flushed as it
 * is written (a crash loses at most the entry in flight). The file rotates at {@code maxBytes} to
 * {@code audit.log.1 … .N}, keeping disk use bounded on small machines.</p>
 */
public final class AuditLog implements AutoCloseable {

    public enum Level {
        INFO, WARN, ERROR, SECURITY
    }

    public enum Category {
        INGEST_FAILURE, INGEST_REFUSED, FILE_ACCESS, PREVIEW, WEB_QUERY, AI_CALL, RUNTIME_ERROR, ADMIN, AUTH, PROJECT
    }

    public static final long DEFAULT_MAX_BYTES = 1L << 20;
    public static final int DEFAULT_KEEP = 3;

    private final Path file;
    private final long maxBytes;
    private final int keep;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private Writer writer;
    private long size;
    private volatile long written;
    private volatile IOException lastError;

    public AuditLog(Path file) {
        this(file, DEFAULT_MAX_BYTES, DEFAULT_KEEP, Clock.systemUTC());
    }

    public AuditLog(Path file, long maxBytes, int keep, Clock clock) {
        this.file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath().normalize();
        this.maxBytes = Math.max(4_096, maxBytes);
        this.keep = Math.max(1, keep);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Path file() {
        return file;
    }

    /** Entries written by this instance. */
    public long written() {
        return written;
    }

    /** Last write failure, if any; auditing never throws into the caller. */
    public IOException lastError() {
        return lastError;
    }

    /**
     * Records one event.
     *
     * @param fields alternating keys and values; {@code null} values are written as {@code -}
     */
    public void record(Level level, Category category, String... fields) {
        StringBuilder line = new StringBuilder(160);
        line.append(Instant.now(clock).truncatedTo(ChronoUnit.MILLIS)).append(' ');
        pad(line, level.name(), 9);
        pad(line, category.name(), 15);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            if (i > 0) {
                line.append(' ');
            }
            line.append(fields[i]).append('=');
            value(line, fields[i + 1]);
        }
        line.append('\n');
        lock.lock();
        try {
            ensureOpen();
            byte[] bytes = line.toString().getBytes(StandardCharsets.UTF_8);
            if (size + bytes.length > maxBytes && size > 0) {
                rotate();
            }
            writer.write(line.toString());
            writer.flush();
            size += bytes.length;
            written++;
        } catch (IOException e) {
            lastError = e;
            closeQuietly();
        } finally {
            lock.unlock();
        }
    }

    private static void pad(StringBuilder sb, String s, int width) {
        sb.append(s);
        for (int i = s.length(); i < width; i++) {
            sb.append(' ');
        }
    }

    private static void value(StringBuilder sb, String raw) {
        if (raw == null) {
            sb.append('-');
            return;
        }
        boolean quote = raw.isEmpty();
        for (int i = 0; i < raw.length() && !quote; i++) {
            char c = raw.charAt(i);
            quote = c == ' ' || c == '"' || c == '=' || c == '\\' || c < 0x20;
        }
        if (!quote) {
            sb.append(raw);
            return;
        }
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private void ensureOpen() throws IOException {
        if (writer != null) {
            return;
        }
        Files.createDirectories(file.getParent());
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        size = Files.size(file);
    }

    private void rotate() throws IOException {
        closeQuietly();
        for (int i = keep - 1; i >= 1; i--) {
            Path from = file.resolveSibling(file.getFileName() + "." + i);
            if (Files.exists(from)) {
                Files.move(from, file.resolveSibling(file.getFileName() + "." + (i + 1)),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
        ensureOpen();
    }

    /**
     * Last {@code n} lines of the current file, read backwards from the end in 8 KB steps — never the whole file.
     */
    public List<String> tail(int n) throws IOException {
        int wanted = Math.max(0, Math.min(n, 10_000));
        lock.lock();
        try {
            if (writer != null) {
                writer.flush();
            }
            ArrayDeque<String> lines = new ArrayDeque<>();
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                long pos = raf.length();
                byte[] block = new byte[8_192];
                java.io.ByteArrayOutputStream partial = new java.io.ByteArrayOutputStream();
                while (pos > 0 && lines.size() <= wanted) {
                    int len = (int) Math.min(block.length, pos);
                    pos -= len;
                    raf.seek(pos);
                    raf.readFully(block, 0, len);
                    for (int i = len - 1; i >= 0; i--) {
                        if (block[i] == '\n') {
                            addReversed(lines, partial);
                            if (lines.size() > wanted) {
                                break;
                            }
                        } else {
                            partial.write(block[i]);
                        }
                    }
                }
                addReversed(lines, partial);
            } catch (NoSuchFileException | java.io.FileNotFoundException e) {
                return List.of();
            }
            List<String> out = new ArrayList<>(lines);
            return out.subList(Math.max(0, out.size() - wanted), out.size());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Copies every entry written at or after {@code since} — from the rotated files (oldest first) and the current
     * log — into {@code target}, streaming line by line. Returns the number of entries exported.
     */
    public int export(Instant since, Path target) throws IOException {
        java.util.Objects.requireNonNull(since, "since must not be null");
        lock.lock();
        try {
            if (writer != null) {
                writer.flush();
            }
            List<Path> sources = new ArrayList<>();
            for (int i = keep; i >= 1; i--) {
                Path rotated = file.resolveSibling(file.getFileName() + "." + i);
                if (Files.isRegularFile(rotated)) {
                    sources.add(rotated);
                }
            }
            if (Files.isRegularFile(file)) {
                sources.add(file);
            }
            Path out = target.toAbsolutePath().normalize();
            if (sources.contains(out)) {
                throw new IOException("choose an export file other than the audit log itself");
            }
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            int count = 0;
            try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                for (Path source : sources) {
                    try (java.io.BufferedReader r = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            int space = line.indexOf(' ');
                            try {
                                if (space > 0 && !Instant.parse(line.substring(0, space)).isBefore(since)) {
                                    w.write(line);
                                    w.write('\n');
                                    count++;
                                }
                            } catch (java.time.format.DateTimeParseException ignored) {
                                // not an entry line
                            }
                        }
                    }
                }
            }
            return count;
        } finally {
            lock.unlock();
        }
    }

    private static void addReversed(ArrayDeque<String> lines, java.io.ByteArrayOutputStream partial) {
        if (partial.size() == 0) {
            return;
        }
        byte[] b = partial.toByteArray();
        for (int i = 0, j = b.length - 1; i < j; i++, j--) {
            byte t = b[i];
            b[i] = b[j];
            b[j] = t;
        }
        lines.addFirst(new String(b, StandardCharsets.UTF_8));
        partial.reset();
    }

    private void closeQuietly() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // nothing more to do for an audit sink
            }
            writer = null;
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closeQuietly();
        } finally {
            lock.unlock();
        }
    }
}
