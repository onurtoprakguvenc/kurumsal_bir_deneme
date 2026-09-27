package org.example.storage;

import org.example.model.DocumentRecord;
import org.example.model.DocumentType;
import org.example.util.Hashing;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.zip.CRC32;

/**
 * Crash-safe binary snapshot of the document store behind the inverted index.
 *
 * <h2>What is persisted</h2>
 * Each document's {@link MetadataRecord} and its normalized chunk text. Postings are <em>not</em> written: they are
 * re-derived on warm-up by feeding the stored chunks straight into the index. Tokenizing already-normalized text is
 * a small fraction of the cost of the original PDF/OOXML extraction (which is never repeated), and not persisting
 * postings keeps the file roughly half the size and immune to tokenizer changes between versions.
 *
 * <h2>File layout</h2>
 * <pre>
 * header  : int MAGIC 'DWBI' | short VERSION | short flags | long savedAtMillis | int docCount | int crc32(header)
 * block * : int length | int crc32(payload) | payload[length]          (one block per document)
 * footer  : int END_MAGIC 'END!' | int docCount | int crc32(all block crcs)
 * payload : sha256[32] | str fileName | str source | str type | long size | int pages | int emptyPages
 *           | long chars | long ingestedAt | str origin | long sourceModified | long parseMillis
 *           | int chunkCount | (int index | int page | long start | str text) * chunkCount
 * str     : int utf8Length | utf8 bytes
 * </pre>
 *
 * <h2>Atomicity</h2>
 * Temp-swap: the snapshot is written to {@code <name>.tmp} and forced to disk, the current file is moved to
 * {@code <name>.bak}, then the temp file is atomically renamed into place. A crash at any point leaves at least one
 * complete, checksummed file; {@link #load()} tries primary → completed temp → backup and validates every block
 * and the footer, so a torn or bit-rotted file is rejected as a whole instead of half-loading an index.
 *
 * <h2>Memory</h2>
 * Writing reuses one growable block buffer; reading decodes strings directly out of the per-block byte array, so
 * the transient overhead is one document's payload. Reads go through a {@link FileChannel} rather than
 * {@code FileChannel.map}: on Windows a mapped file cannot be replaced until the mapping is garbage collected,
 * which would break the atomic rename on the next save.
 */
public final class IndexStorageEngine {

    private static final int MAGIC = 0x44574249;       // "DWBI"
    private static final int END_MAGIC = 0x454E4421;   // "END!"
    private static final short VERSION = 1;
    private static final int HEADER_BYTES = 24;
    private static final int FOOTER_BYTES = 12;
    private static final int BLOCK_PREFIX_BYTES = 8;
    private static final int MAX_BLOCK_BYTES = 256 << 20;
    private static final int MAX_DOCUMENTS = 5_000_000;
    private static final int WRITE_BUFFER = 64 << 10;

    private final Path file;
    private final Path temp;
    private final Path backup;
    private final ReentrantLock saveLock = new ReentrantLock();

    public IndexStorageEngine(Path file) {
        this.file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath().normalize();
        String name = this.file.getFileName().toString();
        this.temp = this.file.resolveSibling(name + ".tmp");
        this.backup = this.file.resolveSibling(name + ".bak");
    }

    public Path file() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file) || Files.isRegularFile(backup) || Files.isRegularFile(temp);
    }

    // ================================================================== results

    /** One restored document with its persisted metadata. */
    public record Entry(MetadataRecord metadata, DocumentRecord document) {
    }

    /** Which on-disk file a load was served from. */
    public enum Origin {
        PRIMARY, RECOVERED_TEMP, BACKUP, NONE
    }

    /**
     * @param origin   file that was loaded ({@link Origin#NONE} on a first start)
     * @param entries  restored documents in snapshot order
     * @param bytes    size of the loaded file
     * @param nanos    wall-clock load time (I/O + decode, excluding indexing)
     * @param savedAt  snapshot timestamp, or {@code null} when nothing was loaded
     * @param problems files that were rejected and why
     */
    public record LoadReport(Origin origin, List<Entry> entries, long bytes, long nanos, Instant savedAt,
                             List<String> problems) {
        public LoadReport {
            entries = List.copyOf(entries);
            problems = List.copyOf(problems);
        }

        public double millis() {
            return nanos / 1_000_000.0;
        }
    }

    public record SaveReport(Path file, int documents, long chunks, long bytes, long nanos) {
        public double millis() {
            return nanos / 1_000_000.0;
        }
    }

    /** Thrown internally when a candidate file fails validation. */
    private static final class CorruptSnapshotException extends IOException {
        CorruptSnapshotException(String message) {
            super(message);
        }
    }

    // ================================================================== save

    /**
     * Atomically replaces the snapshot with {@code documents}. Concurrent calls are serialized. The collection must
     * not be mutated during the call; pass an immutable snapshot such as {@code SearchIndex.documents()}.
     *
     * @param describer produces the metadata to persist for a document (parse latency, source fingerprint)
     */
    public SaveReport save(Collection<DocumentRecord> documents, Function<DocumentRecord, MetadataRecord> describer)
            throws IOException {
        return save(documents, describer, Set.of());
    }

    /**
     * Like {@link #save(Collection, Function)}, and additionally keeps the documents named by {@code carryOver} that
     * exist in the current snapshot (primary, else backup) but are not in memory — for example because the heap
     * ceiling stopped the warm-up before them. Their checksummed blocks are copied verbatim, one block at a time, so
     * a temporary memory limit never turns into a permanent loss of indexed documents.
     */
    public SaveReport save(Collection<DocumentRecord> documents, Function<DocumentRecord, MetadataRecord> describer,
                           Set<String> carryOver) throws IOException {
        Objects.requireNonNull(documents, "documents must not be null");
        Objects.requireNonNull(describer, "describer must not be null");
        Objects.requireNonNull(carryOver, "carryOver must not be null");
        if (documents.size() > MAX_DOCUMENTS) {
            throw new IOException("Too many documents for one snapshot: " + documents.size());
        }
        long started = System.nanoTime();
        saveLock.lock();
        try {
            Files.createDirectories(file.getParent());
            Files.deleteIfExists(temp);
            long chunks = 0;
            long bytes;
            int total;
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(nonClosing(Channels.newOutputStream(channel)), WRITE_BUFFER));
                out.write(headerBytes(documents.size()).array());
                BlockBuffer block = new BlockBuffer();
                DataOutputStream blockOut = new DataOutputStream(block);
                CRC32 blockCrc = new CRC32();
                CRC32 chain = new CRC32();
                ByteBuffer crcBytes = ByteBuffer.allocate(4);
                Set<String> written = new HashSet<>();
                for (DocumentRecord document : documents) {
                    written.add(document.sha256());
                    MetadataRecord meta = Objects.requireNonNull(describer.apply(document), "describer returned null");
                    block.reset();
                    writePayload(blockOut, meta, document);
                    blockOut.flush();
                    if (block.size() > MAX_BLOCK_BYTES) {
                        throw new IOException(document.fileName() + " exceeds the snapshot block limit");
                    }
                    blockCrc.reset();
                    blockCrc.update(block.array(), 0, block.size());
                    int crc = (int) blockCrc.getValue();
                    chain.update(crcBytes.putInt(0, crc).array());
                    out.writeInt(block.size());
                    out.writeInt(crc);
                    out.write(block.array(), 0, block.size());
                    chunks += document.chunks().size();
                }
                int carried = 0;
                if (!carryOver.isEmpty()) {
                    Set<String> pending = new HashSet<>(carryOver);
                    pending.removeAll(written);
                    carried = copyBlocks(pending, out, chain, crcBytes);
                }
                total = documents.size() + carried;
                out.writeInt(END_MAGIC);
                out.writeInt(total);
                out.writeInt((int) chain.getValue());
                out.flush();
                if (carried > 0) {
                    // The header was written before the carried blocks were known; patch its count in place.
                    ByteBuffer header = headerBytes(total);
                    while (header.hasRemaining()) {
                        channel.write(header, header.position());
                    }
                }
                channel.force(true);
                bytes = channel.size();
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            if (Files.exists(file)) {
                move(file, backup);
            }
            move(temp, file);
            return new SaveReport(file, total, chunks, bytes, System.nanoTime() - started);
        } finally {
            saveLock.unlock();
        }
    }

    private static ByteBuffer headerBytes(int docCount) {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(MAGIC).putShort(VERSION).putShort((short) 0).putLong(System.currentTimeMillis()).putInt(docCount);
        CRC32 crc = new CRC32();
        crc.update(header.array(), 0, HEADER_BYTES - 4);
        header.putInt((int) crc.getValue());
        header.flip();
        return header;
    }

    /**
     * Copies the verified blocks of the documents in {@code pending} from the current snapshot files (primary first,
     * then backup) into {@code out}; returns how many were copied. An unreadable or damaged source only ends the scan
     * of that source; the blocks already copied are complete and checksummed.
     */
    private int copyBlocks(Set<String> pending, DataOutputStream out, CRC32 chain, ByteBuffer crcBytes)
            throws IOException {
        int copied = 0;
        CRC32 blockCrc = new CRC32();
        for (Path source : List.of(file, backup)) {
            if (pending.isEmpty() || !Files.isRegularFile(source)) {
                continue;
            }
            try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ)) {
                long size = in.size();
                if (size < HEADER_BYTES + FOOTER_BYTES) {
                    continue;
                }
                ByteBuffer header = readExactly(in, HEADER_BYTES);
                CRC32 crc = new CRC32();
                crc.update(header.array(), 0, HEADER_BYTES - 4);
                if (header.getInt(0) != MAGIC || header.getInt(HEADER_BYTES - 4) != (int) crc.getValue()
                        || header.getShort(4) != VERSION) {
                    continue;
                }
                int docCount = header.getInt(16);
                ByteBuffer prefix = ByteBuffer.allocate(BLOCK_PREFIX_BYTES);
                for (int d = 0; d < docCount && !pending.isEmpty(); d++) {
                    prefix.clear();
                    fill(in, prefix);
                    int length = prefix.getInt(0);
                    int expected = prefix.getInt(4);
                    if (length < 32 || length > MAX_BLOCK_BYTES || length > size - in.position() - FOOTER_BYTES) {
                        break;
                    }
                    ByteBuffer block = readExactly(in, length);
                    blockCrc.reset();
                    blockCrc.update(block.array(), 0, length);
                    if ((int) blockCrc.getValue() != expected) {
                        break;
                    }
                    if (pending.remove(Hashing.hex(Arrays.copyOf(block.array(), 32)))) {
                        out.writeInt(length);
                        out.writeInt(expected);
                        out.write(block.array(), 0, length);
                        chain.update(crcBytes.putInt(0, expected).array());
                        copied++;
                    }
                }
            } catch (CorruptSnapshotException | NoSuchFileException e) {
                // truncated or vanished source: keep what was copied, try the next source
            }
        }
        return copied;
    }

    private static void writePayload(DataOutputStream out, MetadataRecord meta, DocumentRecord document)
            throws IOException {
        if (meta.chunks().size() != document.chunks().size()) {
            throw new IOException("metadata/document chunk count mismatch for " + document.fileName());
        }
        out.write(Hashing.fromHex(meta.sha256()));
        writeString(out, meta.fileName());
        writeString(out, meta.source());
        writeString(out, meta.type().name());
        out.writeLong(meta.sizeBytes());
        out.writeInt(meta.pageCount());
        out.writeInt(meta.emptyPages());
        out.writeLong(meta.charCount());
        out.writeLong(meta.ingestedAtMillis());
        writeString(out, meta.origin());
        out.writeLong(meta.sourceModifiedMillis());
        out.writeLong(meta.parseMillis());
        out.writeInt(meta.chunks().size());
        for (int i = 0; i < meta.chunks().size(); i++) {
            MetadataRecord.ChunkOffset c = meta.chunks().get(i);
            out.writeInt(c.index());
            out.writeInt(c.page());
            out.writeLong(c.startOffset());
            writeString(out, document.chunks().get(i).text());
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Lets the DataOutputStream be flushed without closing the channel before {@code force}. */
    private static OutputStream nonClosing(OutputStream delegate) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                delegate.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                delegate.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                delegate.flush();
            }
        };
    }

    /** ByteArrayOutputStream whose backing array can be read without a defensive copy. */
    private static final class BlockBuffer extends ByteArrayOutputStream {
        BlockBuffer() {
            super(64 << 10);
        }

        byte[] array() {
            return buf;
        }
    }

    // ================================================================== load

    /**
     * Restores the most recent valid snapshot. Never partially succeeds: each candidate is fully validated before
     * any entry is returned. Returns {@link Origin#NONE} with an empty list when no valid file exists.
     */
    public LoadReport load() throws IOException {
        List<Entry> entries = new ArrayList<>();
        LoadReport report = load(new Visitor() {
            @Override
            public boolean wants(MetadataRecord header) {
                return true;
            }

            @Override
            public void entry(Entry entry) {
                entries.add(entry);
            }
        });
        return new LoadReport(report.origin(), entries, report.bytes(), report.nanos(), report.savedAt(),
                report.problems());
    }

    /**
     * Receives a snapshot one document at a time, so a caller can index each entry and let its bytes go before the
     * next one is decoded instead of holding the whole snapshot in memory.
     */
    public interface Visitor {
        /**
         * Decides from the document's metadata alone (its chunk list is empty here) whether its text should be
         * decoded and passed to {@link #entry}.
         */
        boolean wants(MetadataRecord header);

        void entry(Entry entry);
    }

    /**
     * Streaming variant of {@link #load()}: the chosen candidate is fully validated first (checksums, footer and a
     * trial decode of every block, one block in memory at a time), then its wanted entries are decoded and handed to
     * {@code visitor} in file order. The returned report's entry list is empty.
     */
    public LoadReport load(Visitor visitor) throws IOException {
        Objects.requireNonNull(visitor, "visitor must not be null");
        long started = System.nanoTime();
        List<String> problems = new ArrayList<>();
        // A temp file only matters when the primary is gone (crash between the two renames of a save).
        record Candidate(Path path, Origin origin) {
        }
        List<Candidate> candidates = List.of(new Candidate(file, Origin.PRIMARY),
                new Candidate(temp, Origin.RECOVERED_TEMP), new Candidate(backup, Origin.BACKUP));
        boolean primaryExists = Files.isRegularFile(file);
        for (Candidate candidate : candidates) {
            if (candidate.origin() == Origin.RECOVERED_TEMP && primaryExists) {
                continue;
            }
            if (!Files.isRegularFile(candidate.path())) {
                continue;
            }
            try {
                Decoded decoded = read(candidate.path(), visitor);
                return new LoadReport(candidate.origin(), List.of(), decoded.bytes(),
                        System.nanoTime() - started, decoded.savedAt(), problems);
            } catch (CorruptSnapshotException e) {
                problems.add(candidate.path().getFileName() + ": " + e.getMessage());
            } catch (NoSuchFileException e) {
                // Raced with a concurrent save's rename; the next candidate covers it.
            }
        }
        return new LoadReport(Origin.NONE, List.of(), 0, System.nanoTime() - started, null, problems);
    }

    private record Decoded(long bytes, Instant savedAt, int documents) {
    }

    /**
     * Result of checking one snapshot file.
     *
     * @param present   the file exists
     * @param valid     every checksum, the footer and a trial decode of every block passed
     * @param documents documents in the file (when valid)
     * @param problem   why it is not valid, or {@code null}
     */
    public record FileCheck(Path file, Origin origin, boolean present, boolean valid, int documents, long bytes,
                            Instant savedAt, String problem) {
    }

    /**
     * Checks the primary, temp and backup snapshot files without loading anything into an index: the same full
     * validation {@link #load()} performs, one block in memory at a time. Waits for a running save to finish.
     */
    public List<FileCheck> verify() throws IOException {
        List<FileCheck> out = new ArrayList<>();
        saveLock.lock();
        try {
            for (Path path : List.of(file, temp, backup)) {
                Origin origin = path.equals(file) ? Origin.PRIMARY : path.equals(temp) ? Origin.RECOVERED_TEMP
                        : Origin.BACKUP;
                if (!Files.isRegularFile(path)) {
                    out.add(new FileCheck(path, origin, false, false, 0, 0, null, null));
                    continue;
                }
                try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                    Decoded d = scan(channel, null);
                    out.add(new FileCheck(path, origin, true, true, d.documents(), d.bytes(), d.savedAt(), null));
                } catch (CorruptSnapshotException e) {
                    out.add(new FileCheck(path, origin, true, false, 0, Files.size(path), null, e.getMessage()));
                }
            }
        } finally {
            saveLock.unlock();
        }
        return out;
    }

    /** Validates the whole file (pass 1), then streams the wanted entries to {@code visitor} (pass 2). */
    private static Decoded read(Path path, Visitor visitor) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            Decoded decoded = scan(channel, null);
            channel.position(0);
            scan(channel, visitor);
            return decoded;
        }
    }

    /** One pass over an open snapshot; with a null visitor every block is trial-decoded and discarded. */
    private static Decoded scan(FileChannel channel, Visitor visitor) throws IOException {
        long size = channel.size();
        if (size < HEADER_BYTES + FOOTER_BYTES) {
            throw new CorruptSnapshotException("truncated (" + size + " bytes)");
        }
        ByteBuffer header = readExactly(channel, HEADER_BYTES);
        CRC32 crc = new CRC32();
        crc.update(header.array(), 0, HEADER_BYTES - 4);
        if (header.getInt(0) != MAGIC) {
            throw new CorruptSnapshotException("not a snapshot file");
        }
        if (header.getInt(HEADER_BYTES - 4) != (int) crc.getValue()) {
            throw new CorruptSnapshotException("header checksum mismatch");
        }
        short version = header.getShort(4);
        if (version != VERSION) {
            throw new CorruptSnapshotException("unsupported version " + version);
        }
        Instant savedAt = Instant.ofEpochMilli(header.getLong(8));
        int docCount = header.getInt(16);
        if (docCount < 0 || docCount > MAX_DOCUMENTS) {
            throw new CorruptSnapshotException("implausible document count " + docCount);
        }

        CRC32 blockCrc = new CRC32();
        CRC32 chain = new CRC32();
        ByteBuffer prefix = ByteBuffer.allocate(BLOCK_PREFIX_BYTES);
        for (int d = 0; d < docCount; d++) {
            prefix.clear();
            fill(channel, prefix);
            int length = prefix.getInt(0);
            int expected = prefix.getInt(4);
            if (length < 0 || length > MAX_BLOCK_BYTES || length > size - channel.position() - FOOTER_BYTES) {
                throw new CorruptSnapshotException("block " + d + " has invalid length " + length);
            }
            ByteBuffer block = readExactly(channel, length);
            blockCrc.reset();
            blockCrc.update(block.array(), 0, length);
            if ((int) blockCrc.getValue() != expected) {
                throw new CorruptSnapshotException("block " + d + " checksum mismatch");
            }
            chain.update(prefix.array(), 4, 4);
            if (visitor == null) {
                decode(block, d, null);
            } else {
                Entry entry = decode(block, d, visitor::wants);
                if (entry != null) {
                    visitor.entry(entry);
                }
            }
        }
        ByteBuffer footer = readExactly(channel, FOOTER_BYTES);
        if (footer.getInt(0) != END_MAGIC || footer.getInt(4) != docCount
                || footer.getInt(8) != (int) chain.getValue()) {
            throw new CorruptSnapshotException("footer missing or inconsistent (incomplete write)");
        }
        if (channel.position() != size) {
            throw new CorruptSnapshotException("trailing bytes after footer");
        }
        return new Decoded(size, savedAt, docCount);
    }

    /** Decodes one block; returns null when {@code wants} (may be null = everything) declines the document. */
    private static Entry decode(ByteBuffer in, int blockNo, java.util.function.Predicate<MetadataRecord> wants)
            throws CorruptSnapshotException {
        try {
            byte[] hash = new byte[32];
            in.get(hash);
            String sha256 = Hashing.hex(hash);
            String fileName = readString(in);
            String source = readString(in);
            DocumentType type = DocumentType.valueOf(readString(in));
            long sizeBytes = in.getLong();
            int pageCount = in.getInt();
            int emptyPages = in.getInt();
            long charCount = in.getLong();
            long ingestedAt = in.getLong();
            String origin = readString(in);
            long sourceModified = in.getLong();
            long parseMillis = in.getLong();
            if (wants != null && !wants.test(new MetadataRecord(sha256, fileName, source, type, sizeBytes, pageCount,
                    emptyPages, charCount, ingestedAt, origin, sourceModified, parseMillis, List.of()))) {
                return null;
            }
            int chunkCount = in.getInt();
            // Each chunk needs at least 20 bytes, which bounds a corrupt count before allocating.
            if (chunkCount < 0 || chunkCount > in.remaining() / 20) {
                throw new CorruptSnapshotException("block " + blockNo + " has invalid chunk count " + chunkCount);
            }
            List<MetadataRecord.ChunkOffset> offsets = new ArrayList<>(chunkCount);
            List<String> texts = new ArrayList<>(chunkCount);
            for (int i = 0; i < chunkCount; i++) {
                int index = in.getInt();
                int page = in.getInt();
                long start = in.getLong();
                String text = readString(in);
                offsets.add(new MetadataRecord.ChunkOffset(index, page, start, text.length()));
                texts.add(text);
            }
            if (in.hasRemaining()) {
                throw new CorruptSnapshotException("block " + blockNo + " has trailing bytes");
            }
            MetadataRecord meta = new MetadataRecord(sha256, fileName, source, type, sizeBytes, pageCount,
                    emptyPages, charCount, ingestedAt, origin, sourceModified, parseMillis, offsets);
            return new Entry(meta, meta.toDocument(texts));
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            // IllegalArgumentException covers bad enum names, hashes, paths and record invariants.
            throw new CorruptSnapshotException("block " + blockNo + " is malformed: " + e.getMessage());
        }
    }

    /** Decodes in place from the block's backing array: no intermediate byte[] per string. */
    private static String readString(ByteBuffer in) throws CorruptSnapshotException {
        int length = in.getInt();
        if (length < 0 || length > in.remaining()) {
            throw new CorruptSnapshotException("string length " + length + " exceeds block");
        }
        String value = new String(in.array(), in.arrayOffset() + in.position(), length, StandardCharsets.UTF_8);
        in.position(in.position() + length);
        return value;
    }

    private static ByteBuffer readExactly(FileChannel channel, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        fill(channel, buffer);
        buffer.flip();
        return buffer;
    }

    private static void fill(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new CorruptSnapshotException("unexpected end of file");
            }
        }
    }
}
