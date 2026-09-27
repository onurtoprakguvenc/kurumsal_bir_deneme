package org.example.ingest;

import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Streaming PowerPoint 97–2003 (.ppt) extraction with the JDK only.
 *
 * <p>A {@code .ppt} file is an OLE2 Compound File. {@link CompoundFile} reads its header, FAT and directory and
 * exposes the {@code PowerPoint Document} stream as random-access sectors behind a one-sector cache, so the stream
 * is never loaded as a whole; the only structures proportional to the file are the FAT (4 bytes per sector) and
 * the sector list of that one stream.</p>
 *
 * <p>Slides are resolved the way PowerPoint itself does: {@code Current User} → newest {@code UserEditAtom} →
 * persist directories of the whole edit chain → {@code DocumentContainer} → its slide and notes
 * {@code SlideListWithText} lists (presentation order). Each slide's text is taken from its list range
 * (title/body placeholders) and from the {@code TextCharsAtom}/{@code TextBytesAtom} records inside its
 * {@code Slide} container (text boxes, tables); notes are linked through {@code NotesAtom.slideIdRef}. Master
 * slides and date/footer/header/slide-number placeholders are not extracted. When the edit chain is unreadable a
 * linear record scan recovers the text in stream order and says so in the output.</p>
 *
 * <p>Output mirrors {@link PptxTextExtractor}: every slide starts a new chunker page and a
 * {@code === Slayt n ===} boundary, bounded by {@link Limits#maxSlides()} and {@link Limits#maxSlideChars()};
 * heap pressure is watched by the chunker's {@link org.example.core.HeapGuard}.</p>
 */
final class PptTextExtractor {

    /** Extraction summary. */
    record Info(int slides) {
    }

    static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
            (byte) 0xE1};

    private static final String DOCUMENT_STREAM = "PowerPoint Document";
    private static final String CURRENT_USER_STREAM = "Current User";

    private static final int RT_DOCUMENT = 0x03E8;
    private static final int RT_SLIDE = 0x03EE;
    private static final int RT_NOTES = 0x03F0;
    private static final int RT_NOTES_ATOM = 0x03F1;
    private static final int RT_SLIDE_PERSIST_ATOM = 0x03F3;
    private static final int RT_MAIN_MASTER = 0x03F8;
    private static final int RT_SLIDE_SHOW_SLIDE_INFO_ATOM = 0x03F9;
    private static final int RT_TEXT_HEADER_ATOM = 0x0F9F;
    private static final int RT_TEXT_CHARS_ATOM = 0x0FA0;
    private static final int RT_TEXT_BYTES_ATOM = 0x0FA8;
    private static final int RT_SLIDE_LIST_WITH_TEXT = 0x0FF0;
    private static final int RT_USER_EDIT_ATOM = 0x0FF5;
    private static final int RT_CURRENT_USER_ATOM = 0x0FF6;
    private static final int RT_PERSIST_DIRECTORY_ATOM = 0x1772;
    private static final int RT_PROG_TAGS = 0x1388;
    private static final int RT_CRYPT_SESSION = 0x2F14;
    private static final int RT_PLACEHOLDER_ATOM = 0x0BC3;
    private static final int RT_OFFICE_ART_SHAPE = 0xF004;

    /** OEPlaceholderAtom placement ids of date, slide number, footer and header placeholders. */
    private static final Set<Integer> SKIPPED_PLACEHOLDERS = Set.of(0x07, 0x08, 0x09, 0x0A);

    private static final int LIST_SLIDES = 0;
    private static final int LIST_NOTES = 2;

    private static final int TX_TITLE = 0;
    private static final int TX_BODY = 1;
    private static final int TX_OTHER = 4;
    private static final int TX_CENTER_BODY = 5;
    private static final int TX_CENTER_TITLE = 6;
    private static final int TX_HALF_BODY = 7;
    private static final int TX_QUARTER_BODY = 8;

    private static final int HEADER_TOKEN_ENCRYPTED = 0xF3D1C4DF;
    private static final int SLIDE_HIDDEN_FLAG = 0x0004;
    private static final int MAX_EDITS = 10_000;
    private static final int MAX_PERSIST_OBJECTS = 262_144;

    private PptTextExtractor() {
    }

    /** True when {@code file} is an OLE2 container holding a PowerPoint document stream; never throws. */
    static boolean isPowerPoint(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return CompoundFile.open(channel).find(DOCUMENT_STREAM) != null;
        } catch (IOException | IngestionException | RuntimeException e) {
            return false;
        }
    }

    static Info extract(Path file, TextChunker sink, Limits limits) throws IngestionException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            CompoundFile cfb = CompoundFile.open(channel);
            CompoundFile.Entry document = cfb.find(DOCUMENT_STREAM);
            if (document == null) {
                if (cfb.find("EncryptedPackage") != null) {
                    throw new IngestionException(Reason.ENCRYPTED, "Presentation is password protected");
                }
                throw new IngestionException(Reason.UNSUPPORTED,
                        "OLE2 file is not a PowerPoint 97-2003 presentation (legacy .doc/.xls are not supported)");
            }
            Records doc = new Records(cfb.stream(document));
            CompoundFile.Entry user = cfb.find(CURRENT_USER_STREAM);
            Deck deck = user == null ? null
                    : Deck.fromEditChain(doc, new Records(cfb.stream(user)), limits.maxSlides());
            boolean recovered = deck == null;
            if (recovered) {
                deck = Deck.fromLinearScan(doc, limits.maxSlides());
            }
            return render(deck, doc, sink, limits, recovered);
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot read presentation: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IngestionException(Reason.CORRUPTED, "Malformed presentation: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    // ============================================================================================ rendering

    private static Info render(Deck deck, Records doc, TextChunker sink, Limits limits, boolean recovered)
            throws IOException {
        int number = 0;
        for (SlideRef slide : deck.slides) {
            if (sink.truncated()) {
                break;
            }
            number++;
            sink.startPage(number);
            SlideRef notes = deck.notesBySlideId.get(slide.slideId());
            sink.appendPreformatted(renderSlide(doc, slide, notes, number, limits, recovered && number == 1));
        }
        if (!sink.truncated() && deck.totalSlides > number) {
            sink.append("> Not: " + limits.maxSlides() + " slayt sınırına ulaşıldı; kalan "
                    + (deck.totalSlides - number) + " slayt dahil edilmedi.\n");
        }
        return new Info(number);
    }

    private static CharSequence renderSlide(Records doc, SlideRef slide, SlideRef notes, int number, Limits limits,
                                            boolean recoveryNote) throws IOException {
        Texts texts = new Texts(limits.maxSlideChars());
        boolean hidden = collect(doc, slide, RT_SLIDE, texts);
        Texts noteTexts = new Texts(limits.maxSlideChars());
        if (notes != null) {
            collect(doc, notes, RT_NOTES, noteTexts);
        }

        Output out = new Output(limits.maxSlideChars());
        out.line("=== Slayt " + number + " ===");
        if (recoveryNote) {
            out.line("> Not: Sunum dizini okunamadı; metin kurtarma modunda çıkarıldı (sıra ve notlar eksik olabilir).");
        }
        if (hidden) {
            out.line("(gizli slayt)");
        }
        int headerLines = out.lines;
        for (Text t : texts.items) {
            if (t.type() == TX_TITLE || t.type() == TX_CENTER_TITLE) {
                out.line("Başlık: " + String.join(" / ", paragraphs(t.value())));
            }
        }
        for (Text t : texts.items) {
            if (t.type() == TX_CENTER_BODY) {
                out.line("Alt başlık: " + String.join(" / ", paragraphs(t.value())));
            }
        }
        for (Text t : texts.items) {
            boolean bullet = t.type() == TX_BODY || t.type() == TX_HALF_BODY || t.type() == TX_QUARTER_BODY;
            if (t.type() != TX_TITLE && t.type() != TX_CENTER_TITLE && t.type() != TX_CENTER_BODY) {
                paragraphs(t.value()).forEach(p -> out.line(bullet ? "- " + p : p));
            }
        }
        if (out.lines == headerLines) {
            out.line("(metin içermiyor)");
        }
        List<String> noteLines = new ArrayList<>();
        noteTexts.items.forEach(t -> noteLines.addAll(paragraphs(t.value())));
        if (!noteLines.isEmpty()) {
            out.line("Konuşmacı notları:");
            noteLines.forEach(out::line);
        }
        return out.finish();
    }

    /** PPT paragraphs end with CR; vertical tab is a soft line break inside a paragraph. */
    private static List<String> paragraphs(String value) {
        List<String> result = new ArrayList<>();
        for (String p : value.split("[\r\n]")) {
            String line = TableBlockWriter.oneLine(p.replace('\u000B', ' '), p.length());
            if (!line.isEmpty()) {
                result.add(line);
            }
        }
        return result;
    }

    /**
     * Collects the text of one slide or notes page: first its {@code SlideListWithText} range, then the text
     * atoms inside its container (verified to be of {@code containerType}).
     *
     * @return true when the container marks the slide as hidden
     */
    private static boolean collect(Records doc, SlideRef ref, int containerType, Texts texts) throws IOException {
        if (ref.textStart() >= 0) {
            walk(doc, ref.textStart(), ref.textEnd(), texts);
        }
        if (ref.container() >= 0) {
            Header h = doc.header(ref.container(), doc.size());
            if (h != null && h.type() == containerType && h.container()) {
                return walk(doc, h.body(), h.end(), texts);
            }
        }
        return false;
    }

    /** Flattened walk over the records in {@code [start, end)}; returns the hidden flag if one was seen. */
    private static boolean walk(Records doc, long start, long end, Texts texts) throws IOException {
        boolean hidden = false;
        int textType = TX_OTHER;
        long shapeEnd = -1;
        long skipUntil = -1;
        Deque<Long> ends = new ArrayDeque<>();
        long pos = start;
        while (!texts.full()) {
            while (!ends.isEmpty() && pos >= ends.peek()) {
                ends.pop();
            }
            long limit = ends.isEmpty() ? end : ends.peek();
            Header h = doc.header(pos, limit);
            if (h == null) {
                if (ends.isEmpty()) {
                    break;
                }
                pos = ends.pop();
                continue;
            }
            if (h.container() && h.type() != RT_PROG_TAGS) {
                if (h.type() == RT_OFFICE_ART_SHAPE) {
                    shapeEnd = h.end();
                    textType = TX_OTHER;
                }
                ends.push(h.end());
                pos = h.body();
                continue;
            }
            switch (h.type()) {
                case RT_TEXT_HEADER_ATOM -> {
                    if (h.length() >= 4) {
                        textType = doc.i32(h.body());
                    }
                }
                case RT_PLACEHOLDER_ATOM -> {
                    // The shape's client data precedes its text box, so the whole shape can be skipped.
                    if (h.length() >= 8 && SKIPPED_PLACEHOLDERS.contains(doc.u16(h.body() + 4) & 0xFF)) {
                        skipUntil = shapeEnd;
                    }
                }
                case RT_TEXT_CHARS_ATOM -> {
                    if (pos < skipUntil) {
                        break;
                    }
                    int bytes = (int) Math.min(h.length(), ((long) texts.remaining() + 1) * 2) & ~1;
                    texts.add(textType, doc.string(h.body(), bytes, StandardCharsets.UTF_16LE));
                }
                case RT_TEXT_BYTES_ATOM -> {
                    if (pos < skipUntil) {
                        break;
                    }
                    int bytes = (int) Math.min(h.length(), (long) texts.remaining() + 1);
                    texts.add(textType, doc.string(h.body(), bytes, StandardCharsets.ISO_8859_1));
                }
                case RT_SLIDE_SHOW_SLIDE_INFO_ATOM -> {
                    if (h.length() >= 12) {
                        hidden = (doc.u16(h.body() + 10) & SLIDE_HIDDEN_FLAG) != 0;
                    }
                }
                default -> {
                }
            }
            pos = h.end();
        }
        return hidden;
    }

    private record Text(int type, String value) {
    }

    /** Text atoms of one page within a character budget; repeated atoms (list + shape copies) are kept once. */
    private static final class Texts {
        final List<Text> items = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private int remaining;

        Texts(int budget) {
            this.remaining = budget;
        }

        int remaining() {
            return remaining;
        }

        boolean full() {
            return remaining <= 0;
        }

        void add(int type, String value) {
            String trimmed = value.strip();
            // "*" is the slide-number / date field placeholder, not content.
            if (trimmed.isEmpty() || "*".equals(trimmed) || !seen.add(type + ":" + trimmed)) {
                return;
            }
            String kept = trimmed.length() > remaining ? trimmed.substring(0, remaining) : trimmed;
            remaining -= kept.length();
            items.add(new Text(type, kept));
        }
    }

    /** Bounded slide text builder; clipping is announced once. */
    private static final class Output {
        private final StringBuilder text = new StringBuilder(512);
        private final int max;
        private boolean clipped;
        int lines;

        Output(int max) {
            this.max = max;
        }

        void line(String value) {
            if (clipped) {
                return;
            }
            if (text.length() + value.length() + 1 > max) {
                clipped = true;
                text.append("> Not: Slayt metni ").append(max).append(" karakterde kısaltıldı.\n");
                return;
            }
            text.append(value).append('\n');
            lines++;
        }

        CharSequence finish() {
            return text.append('\n');
        }
    }

    // ============================================================================================ slide index

    /**
     * One slide or notes page: its {@code SlideListWithText} range ({@code -1} when absent) and the stream offset
     * of its container ({@code -1} when unknown). For notes, {@code slideId} is the slide the notes belong to.
     */
    private record SlideRef(int slideId, long container, long textStart, long textEnd) {
    }

    private record SlideList(List<SlideRef> refs, int total) {
    }

    private static final class Deck {
        final List<SlideRef> slides;
        final int totalSlides;
        final Map<Integer, SlideRef> notesBySlideId = new HashMap<>();

        private Deck(SlideList slides) {
            this.slides = slides.refs();
            this.totalSlides = slides.total();
        }

        /**
         * Resolves slides through the persist directory of the whole edit chain (newest entry wins).
         *
         * @return null when the chain is inconsistent, so the caller falls back to a linear scan
         */
        static Deck fromEditChain(Records doc, Records user, int maxSlides) throws IngestionException {
            try {
                Header current = user.header(0, user.size());
                if (current == null || current.type() != RT_CURRENT_USER_ATOM || current.length() < 12) {
                    return null;
                }
                if (user.i32(current.body() + 4) == HEADER_TOKEN_ENCRYPTED) {
                    throw new IngestionException(Reason.ENCRYPTED, "Presentation is password protected");
                }
                Map<Integer, Long> persist = new HashMap<>();
                Set<Long> visited = new HashSet<>();
                int documentRef = -1;
                long edit = user.u32(current.body() + 8);
                while (true) {
                    if (!visited.add(edit) || visited.size() > MAX_EDITS) {
                        return null;
                    }
                    Header atom = doc.header(edit, doc.size());
                    if (atom == null || atom.type() != RT_USER_EDIT_ATOM || atom.length() < 28) {
                        return null;
                    }
                    if (documentRef < 0) {
                        if (atom.length() >= 32) {
                            // encryptSessionPersistIdRef exists only in encrypted documents.
                            throw new IngestionException(Reason.ENCRYPTED, "Presentation is password protected");
                        }
                        documentRef = doc.i32(atom.body() + 16);
                    }
                    Header directory = doc.header(doc.u32(atom.body() + 12), doc.size());
                    if (directory == null || directory.type() != RT_PERSIST_DIRECTORY_ATOM
                            || !readPersistDirectory(doc, directory, persist)) {
                        return null;
                    }
                    long previous = doc.u32(atom.body() + 8);
                    if (previous == 0) {
                        break;
                    }
                    edit = previous;
                }

                Long documentOffset = persist.get(documentRef);
                Header document = documentOffset == null ? null : doc.header(documentOffset, doc.size());
                if (document == null || document.type() != RT_DOCUMENT || !document.container()) {
                    return null;
                }
                SlideList slides = new SlideList(List.of(), 0);
                SlideList notes = new SlideList(List.of(), 0);
                for (Header h = doc.header(document.body(), document.end()); h != null;
                     h = doc.header(h.end(), document.end())) {
                    if (h.type() == RT_SLIDE_LIST_WITH_TEXT && h.container()) {
                        if (h.instance() == LIST_SLIDES) {
                            slides = slideList(doc, h, persist, maxSlides);
                        } else if (h.instance() == LIST_NOTES) {
                            notes = slideList(doc, h, persist, maxSlides * 2);
                        }
                    }
                }
                Deck deck = new Deck(slides);
                for (SlideRef note : notes.refs()) {
                    int slideId = notesOwner(doc, note.container());
                    if (slideId != 0) {
                        deck.notesBySlideId.putIfAbsent(slideId,
                                new SlideRef(slideId, note.container(), note.textStart(), note.textEnd()));
                    }
                }
                return deck;
            } catch (EOFException e) {
                return null;
            } catch (IOException e) {
                throw new IngestionException(Reason.IO_ERROR, "Cannot read presentation: " + e.getMessage(), e);
            }
        }

        /**
         * Recovery path: the newest slide list found in stream order supplies the slides; when it carries no text
         * ranges, every {@code Slide} container outside the masters becomes a slide.
         */
        static Deck fromLinearScan(Records doc, int maxSlides) throws IOException, IngestionException {
            SlideList lastList = new SlideList(List.of(), 0);
            List<SlideRef> containers = new ArrayList<>();
            int containerCount = 0;
            Deque<Long> ends = new ArrayDeque<>();
            long pos = 0;
            while (true) {
                while (!ends.isEmpty() && pos >= ends.peek()) {
                    ends.pop();
                }
                long limit = ends.isEmpty() ? doc.size() : ends.peek();
                Header h = doc.header(pos, limit);
                if (h == null) {
                    if (ends.isEmpty()) {
                        break;
                    }
                    pos = ends.pop();
                    continue;
                }
                if (h.type() == RT_CRYPT_SESSION) {
                    throw new IngestionException(Reason.ENCRYPTED, "Presentation is password protected");
                }
                if (h.container() && h.type() == RT_SLIDE_LIST_WITH_TEXT && h.instance() == LIST_SLIDES) {
                    lastList = slideList(doc, h, Map.of(), maxSlides);
                    pos = h.end();
                } else if (h.container() && h.type() == RT_SLIDE) {
                    if (containers.size() < maxSlides) {
                        containers.add(new SlideRef(0, h.pos(), -1, -1));
                    }
                    containerCount++;
                    pos = h.end();
                } else if (h.container() && h.type() != RT_MAIN_MASTER && h.type() != RT_NOTES
                        && h.type() != RT_PROG_TAGS) {
                    ends.push(h.end());
                    pos = h.body();
                } else {
                    pos = h.end();
                }
            }
            boolean listHasText = lastList.refs().stream().anyMatch(r -> r.textEnd() > r.textStart());
            return listHasText ? new Deck(lastList) : new Deck(new SlideList(containers, containerCount));
        }

        private static boolean readPersistDirectory(Records doc, Header directory, Map<Integer, Long> persist)
                throws IOException {
            long p = directory.body();
            while (p + 4 <= directory.end()) {
                long entry = doc.u32(p);
                p += 4;
                int first = (int) (entry & 0xFFFFF);
                int count = (int) (entry >>> 20);
                for (int k = 0; k < count && p + 4 <= directory.end(); k++, p += 4) {
                    persist.putIfAbsent(first + k, doc.u32(p));
                    if (persist.size() > MAX_PERSIST_OBJECTS) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** Splits a {@code SlideListWithText} at its {@code SlidePersistAtom}s; keeps at most {@code max} refs. */
        private static SlideList slideList(Records doc, Header list, Map<Integer, Long> persist, int max)
                throws IOException {
            List<SlideRef> refs = new ArrayList<>();
            int total = 0;
            int persistId = -1;
            int slideId = 0;
            long start = -1;
            long pos = list.body();
            for (Header h = doc.header(pos, list.end()); h != null; h = doc.header(pos, list.end())) {
                if (h.type() == RT_SLIDE_PERSIST_ATOM && h.length() >= 16) {
                    if (start >= 0 && refs.size() < max) {
                        refs.add(new SlideRef(slideId, persist.getOrDefault(persistId, -1L), start, h.pos()));
                    }
                    total += start >= 0 ? 1 : 0;
                    persistId = doc.i32(h.body());
                    slideId = doc.i32(h.body() + 12);
                    start = h.end();
                }
                pos = h.end();
            }
            if (start >= 0) {
                if (refs.size() < max) {
                    refs.add(new SlideRef(slideId, persist.getOrDefault(persistId, -1L), start, pos));
                }
                total++;
            }
            return new SlideList(refs, total);
        }

        /** {@code NotesAtom.slideIdRef} of the notes container at {@code offset}, or 0. */
        private static int notesOwner(Records doc, long offset) throws IOException {
            Header notes = offset < 0 ? null : doc.header(offset, doc.size());
            if (notes == null || notes.type() != RT_NOTES || !notes.container()) {
                return 0;
            }
            for (Header h = doc.header(notes.body(), notes.end()); h != null; h = doc.header(h.end(), notes.end())) {
                if (h.type() == RT_NOTES_ATOM && h.length() >= 4) {
                    return doc.i32(h.body());
                }
            }
            return 0;
        }
    }

    // ============================================================================================ records

    /** PowerPoint record header: 4-bit version, 12-bit instance, 16-bit type, 32-bit body length. */
    private record Header(long pos, int version, int instance, int type, long length) {
        long body() {
            return pos + 8;
        }

        long end() {
            return pos + 8 + length;
        }

        boolean container() {
            return version == 0xF;
        }
    }

    /** Little-endian accessors over a compound-file stream. */
    private static final class Records {
        private final CompoundFile.Stream stream;
        private final byte[] scratch = new byte[8];

        Records(CompoundFile.Stream stream) {
            this.stream = stream;
        }

        long size() {
            return stream.size();
        }

        /** The record at {@code pos}, or null when it does not fit completely before {@code limit}. */
        Header header(long pos, long limit) throws IOException {
            if (pos < 0 || pos + 8 > limit || limit > stream.size()) {
                return null;
            }
            stream.read(pos, scratch, 0, 8);
            int verInst = (scratch[0] & 0xFF) | (scratch[1] & 0xFF) << 8;
            int type = (scratch[2] & 0xFF) | (scratch[3] & 0xFF) << 8;
            long length = Integer.toUnsignedLong(int32(scratch, 4));
            if (pos + 8 + length > limit) {
                return null;
            }
            return new Header(pos, verInst & 0xF, verInst >>> 4, type, length);
        }

        int u16(long pos) throws IOException {
            stream.read(pos, scratch, 0, 2);
            return (scratch[0] & 0xFF) | (scratch[1] & 0xFF) << 8;
        }

        int i32(long pos) throws IOException {
            stream.read(pos, scratch, 0, 4);
            return int32(scratch, 0);
        }

        long u32(long pos) throws IOException {
            return Integer.toUnsignedLong(i32(pos));
        }

        String string(long pos, int bytes, java.nio.charset.Charset charset) throws IOException {
            byte[] data = new byte[bytes];
            stream.read(pos, data, 0, bytes);
            return new String(data, charset);
        }

        private static int int32(byte[] b, int off) {
            return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
        }
    }

    // ============================================================================================ OLE2 container

    /**
     * Minimal read-only OLE2 Compound File reader (MS-CFB v3/v4): header, DIFAT → FAT, mini FAT, and the direct
     * children of the root storage. Streams are read sector by sector through {@link FileChannel} positional reads.
     */
    static final class CompoundFile {

        private static final int ENDOFCHAIN = -2;
        private static final int DIR_ENTRY_BYTES = 128;
        private static final int TYPE_STREAM = 2;
        private static final int TYPE_ROOT = 5;
        private static final int HEADER_DIFAT_ENTRIES = 109;

        record Entry(String name, int type, int start, long size) {
        }

        private final FileChannel channel;
        private final int sectorSize;
        private final long sectorCount;
        private final int miniCutoff;
        private final int[] fat;
        private final int[] miniFat;
        private final Entry root;
        private final Map<String, Entry> children;
        private Stream miniStream;

        private CompoundFile(FileChannel channel, int sectorSize, long sectorCount, int miniCutoff, int[] fat,
                             int[] miniFat, Entry root, Map<String, Entry> children) {
            this.channel = channel;
            this.sectorSize = sectorSize;
            this.sectorCount = sectorCount;
            this.miniCutoff = miniCutoff;
            this.fat = fat;
            this.miniFat = miniFat;
            this.root = root;
            this.children = children;
        }

        static CompoundFile open(FileChannel channel) throws IOException, IngestionException {
            long fileSize = channel.size();
            ByteBuffer header = readAt(channel, 0, 512);
            for (int i = 0; i < OLE2_MAGIC.length; i++) {
                if (header.get(i) != OLE2_MAGIC[i]) {
                    throw corrupted("missing OLE2 signature");
                }
            }
            int shift = header.getShort(0x1E) & 0xFFFF;
            if ((header.getShort(0x1C) & 0xFFFF) != 0xFFFE || (shift != 9 && shift != 12)
                    || (header.getShort(0x20) & 0xFFFF) != 6) {
                throw corrupted("unsupported compound file header");
            }
            int sectorSize = 1 << shift;
            long sectorCount = Math.max(0, (fileSize - sectorSize + sectorSize - 1) / sectorSize);
            int perSector = sectorSize / 4;
            int fatSectors = header.getInt(0x2C);
            int firstDir = header.getInt(0x30);
            int miniCutoff = header.getInt(0x38);
            int firstMiniFat = header.getInt(0x3C);
            int miniFatSectors = header.getInt(0x40);
            int firstDifat = header.getInt(0x44);
            int difatSectors = header.getInt(0x48);
            if (fatSectors < 1 || fatSectors > sectorCount || miniCutoff <= 0 || miniFatSectors < 0
                    || miniFatSectors > sectorCount || difatSectors < 0 || difatSectors > sectorCount) {
                throw corrupted("invalid sector counts");
            }

            // Only the FAT sectors that describe sectors actually present in the file are loaded.
            int needed = (int) Math.min(fatSectors, (sectorCount + perSector - 1) / perSector);
            int[] fatLocations = new int[needed];
            int found = 0;
            for (int i = 0; i < HEADER_DIFAT_ENTRIES && found < needed; i++) {
                fatLocations[found++] = header.getInt(0x4C + i * 4);
            }
            int difat = firstDifat;
            for (int d = 0; d < difatSectors && found < needed; d++) {
                ByteBuffer sector = readSector(channel, sectorSize, sectorCount, difat);
                for (int i = 0; i < perSector - 1 && found < needed; i++) {
                    fatLocations[found++] = sector.getInt(i * 4);
                }
                difat = sector.getInt((perSector - 1) * 4);
            }
            if (found < needed) {
                throw corrupted("truncated FAT");
            }
            int[] fat = new int[(int) Math.min((long) needed * perSector, sectorCount)];
            for (int s = 0; s < needed; s++) {
                ByteBuffer sector = readSector(channel, sectorSize, sectorCount, fatLocations[s]);
                for (int i = 0; i < perSector && s * perSector + i < fat.length; i++) {
                    fat[s * perSector + i] = sector.getInt(i * 4);
                }
            }

            int[] miniFatChain = openChain(firstMiniFat, miniFatSectors, fat, sectorCount);
            int[] miniFat = new int[miniFatChain.length * perSector];
            for (int s = 0; s < miniFatChain.length; s++) {
                ByteBuffer sector = readSector(channel, sectorSize, sectorCount, miniFatChain[s]);
                for (int i = 0; i < perSector; i++) {
                    miniFat[s * perSector + i] = sector.getInt(i * 4);
                }
            }

            int[] dirChain = openChain(firstDir, -1, fat, sectorCount);
            int perDirSector = sectorSize / DIR_ENTRY_BYTES;
            long entryCount = (long) dirChain.length * perDirSector;
            Entry root = null;
            Map<String, Entry> children = new HashMap<>();
            if (entryCount > 0) {
                ByteBuffer rootBuf = readEntry(channel, sectorSize, sectorCount, dirChain, perDirSector, 0);
                root = entry(rootBuf, shift);
                if (root.type() != TYPE_ROOT) {
                    throw corrupted("missing root storage");
                }
                // Direct children of the root form a red-black tree: left/right siblings, starting at root.child.
                Deque<Integer> pending = new ArrayDeque<>();
                pending.push(rootBuf.getInt(0x4C));
                // Only real entries count against the budget (empty sibling links are -1), and each entry is
                // visited once, so a small directory is walked completely and a cyclic one still terminates.
                java.util.Set<Integer> seen = new java.util.HashSet<>();
                while (!pending.isEmpty() && seen.size() < entryCount) {
                    int id = pending.pop();
                    if (id < 0 || id >= entryCount || !seen.add(id)) {
                        continue;
                    }
                    ByteBuffer buf = readEntry(channel, sectorSize, sectorCount, dirChain, perDirSector, id);
                    Entry e = entry(buf, shift);
                    children.putIfAbsent(e.name().toLowerCase(java.util.Locale.ROOT), e);
                    pending.push(buf.getInt(0x44));
                    pending.push(buf.getInt(0x48));
                }
            }
            if (root == null) {
                throw corrupted("empty directory");
            }
            return new CompoundFile(channel, sectorSize, sectorCount, miniCutoff, fat, miniFat, root, children);
        }

        /** A stream among the root's direct children (names compare case-insensitively), or null. */
        Entry find(String name) {
            Entry e = children.get(name.toLowerCase(java.util.Locale.ROOT));
            return e != null && e.type() == TYPE_STREAM ? e : null;
        }

        Stream stream(Entry entry) throws IngestionException, IOException {
            if (entry.size() < miniCutoff) {
                if (miniStream == null) {
                    long rootUnits = (root.size() + sectorSize - 1) / sectorSize;
                    miniStream = new Stream(channel, null, sectorSize,
                            chain(root.start(), rootUnits, fat, sectorCount), root.size());
                }
                long units = (entry.size() + 63) / 64;
                return new Stream(channel, miniStream, 64,
                        chain(entry.start(), units, miniFat, miniStream.size() / 64), entry.size());
            }
            long units = (entry.size() + sectorSize - 1) / sectorSize;
            return new Stream(channel, null, sectorSize, chain(entry.start(), units, fat, sectorCount), entry.size());
        }

        private static Entry entry(ByteBuffer buf, int shift) {
            int nameBytes = Math.min(64, buf.getShort(0x40) & 0xFFFF);
            char[] name = new char[Math.max(0, nameBytes / 2 - 1)];
            for (int i = 0; i < name.length; i++) {
                name[i] = buf.getChar(i * 2);
            }
            long size = shift == 9 ? Integer.toUnsignedLong(buf.getInt(0x78)) : buf.getLong(0x78);
            return new Entry(new String(name), buf.get(0x42) & 0xFF, buf.getInt(0x74), size);
        }

        /** Sector chain of exactly {@code units} entries starting at {@code start}. */
        private static int[] chain(int start, long units, int[] table, long unitCount) throws IngestionException {
            if (units > unitCount || units > Integer.MAX_VALUE) {
                throw corrupted("stream is larger than its container");
            }
            int[] result = new int[(int) units];
            int s = start;
            for (int i = 0; i < result.length; i++) {
                if (s < 0 || s >= table.length || s >= unitCount) {
                    throw corrupted("broken sector chain");
                }
                result[i] = s;
                s = table[s];
            }
            return result;
        }

        /** Chain until ENDOFCHAIN ({@code expected < 0}) or of {@code expected} sectors. */
        private static int[] openChain(int start, int expected, int[] fat, long sectorCount) throws IngestionException {
            if (expected >= 0) {
                return expected == 0 ? new int[0] : chain(start, expected, fat, sectorCount);
            }
            List<Integer> sectors = new ArrayList<>();
            for (int s = start; s != ENDOFCHAIN; s = fat[s]) {
                if (s < 0 || s >= fat.length || sectors.size() >= sectorCount) {
                    throw corrupted("broken directory chain");
                }
                sectors.add(s);
            }
            return sectors.stream().mapToInt(Integer::intValue).toArray();
        }

        private static ByteBuffer readEntry(FileChannel channel, int sectorSize, long sectorCount, int[] dirChain,
                                            int perDirSector, int id) throws IOException, IngestionException {
            long offset = (long) (dirChain[id / perDirSector] + 1) * sectorSize
                    + (long) (id % perDirSector) * DIR_ENTRY_BYTES;
            return readAt(channel, offset, DIR_ENTRY_BYTES);
        }

        private static ByteBuffer readSector(FileChannel channel, int sectorSize, long sectorCount, int sector)
                throws IOException, IngestionException {
            if (sector < 0 || sector >= sectorCount) {
                throw corrupted("sector " + sector + " is outside the file");
            }
            return readAt(channel, (long) (sector + 1) * sectorSize, sectorSize);
        }

        private static ByteBuffer readAt(FileChannel channel, long offset, int length)
                throws IOException, IngestionException {
            ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
            fill(channel, offset, buf);
            if (buf.hasRemaining()) {
                throw corrupted("unexpected end of file");
            }
            return buf.flip();
        }

        private static void fill(FileChannel channel, long offset, ByteBuffer buf) throws IOException {
            while (buf.hasRemaining()) {
                int n = channel.read(buf, offset + buf.position());
                if (n < 0) {
                    return;
                }
            }
        }

        private static IngestionException corrupted(String detail) {
            return new IngestionException(Reason.CORRUPTED, "Corrupted OLE2 container: " + detail);
        }

        /**
         * A stream as a list of fixed-size units: file sectors, or 64-byte mini sectors inside the mini stream.
         * One unit is cached, so sequential small reads (record headers) cost one positional read per sector.
         */
        static final class Stream {
            private final FileChannel channel;
            private final Stream parent;
            private final int unitSize;
            private final int[] units;
            private final long size;
            private final byte[] cache;
            private int cachedUnit = -1;

            Stream(FileChannel channel, Stream parent, int unitSize, int[] units, long size) {
                this.channel = channel;
                this.parent = parent;
                this.unitSize = unitSize;
                this.units = units;
                this.size = size;
                this.cache = new byte[unitSize];
            }

            long size() {
                return size;
            }

            void read(long pos, byte[] dst, int off, int len) throws IOException {
                if (pos < 0 || len < 0 || pos + len > size) {
                    throw new EOFException("read past end of stream at " + pos);
                }
                while (len > 0) {
                    int unit = (int) (pos / unitSize);
                    int within = (int) (pos % unitSize);
                    load(unit);
                    int n = Math.min(len, unitSize - within);
                    System.arraycopy(cache, within, dst, off, n);
                    pos += n;
                    off += n;
                    len -= n;
                }
            }

            private void load(int unit) throws IOException {
                if (unit == cachedUnit) {
                    return;
                }
                java.util.Arrays.fill(cache, (byte) 0);
                long physical = (long) units[unit] * unitSize;
                if (parent != null) {
                    parent.read(physical, cache, 0, (int) Math.min(unitSize, parent.size() - physical));
                } else {
                    // Header occupies sector -1; a truncated last sector reads as zeros.
                    fill(channel, physical + unitSize, ByteBuffer.wrap(cache));
                }
                cachedUnit = unit;
            }
        }
    }
}
