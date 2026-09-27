package org.example.ingest;

import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Read-only view of an Office Open XML package built on {@link ZipFile} (central directory only, parts are
 * inflated on demand) and StAX. Every part stream is byte-budgeted and every cursor enforces the XML depth limit,
 * so no part can inflate or nest beyond {@link Limits}.
 */
final class OoxmlPackage implements AutoCloseable {

    static final String OFFICE_DOCUMENT = "/officeDocument";

    /** A resolved package relationship; {@code target} is an absolute part name without a leading slash. */
    record Relationship(String id, String type, String target) {

        boolean typeEndsWith(String suffix) {
            return type != null && type.endsWith(suffix);
        }
    }

    private final ZipFile zip;
    private final Limits limits;
    private final long[] budget;

    private OoxmlPackage(ZipFile zip, Limits limits) {
        this.zip = zip;
        this.limits = limits;
        this.budget = new long[]{limits.maxUncompressedBytes()};
    }

    /** Rejects encrypted/legacy containers and ZIP bombs, then opens the package. */
    static OoxmlPackage open(Path file, Limits limits, String format) throws IngestionException {
        OoxmlGuards.rejectOle2(file, format, legacyName(format));
        OoxmlGuards.preflight(file, limits, format);
        try {
            return new OoxmlPackage(new ZipFile(file.toFile(), StandardCharsets.UTF_8), limits);
        } catch (ZipException e) {
            throw new IngestionException(Reason.CORRUPTED, "Corrupted " + format + " archive: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot open " + format + ": " + e.getMessage(), e);
        }
    }

    private static String legacyName(String format) {
        return switch (format) {
            case "XLSX" -> "Excel (.xls)";
            case "PPTX" -> "PowerPoint (.ppt)";
            default -> "Word (.doc)";
        };
    }

    /** Null-safe namespace membership test ({@code Set.of(...)} rejects {@code contains(null)}). */
    static boolean in(Set<String> namespaces, String namespace) {
        return namespace != null && namespaces.contains(namespace);
    }

    boolean has(String part) {
        return part != null && zip.getEntry(part) != null;
    }

    /** Declared uncompressed size of a part, or -1. */
    long declaredSize(String part) {
        ZipEntry entry = zip.getEntry(part);
        return entry == null ? -1 : entry.getSize();
    }

    /** Part names matching {@code filter}, in central-directory order. */
    List<String> partNames(Predicate<String> filter) {
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (filter.test(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /** Opens a budget- and depth-limited StAX cursor over a part. */
    XmlCursor open(String part) throws IOException, XMLStreamException {
        ZipEntry entry = zip.getEntry(part);
        if (entry == null) {
            throw new IOException("Missing package part " + part);
        }
        InputStream in = OoxmlGuards.bounded(zip.getInputStream(entry), limits.maxPartBytes(), budget);
        try {
            return new XmlCursor(OoxmlGuards.xmlInputFactory().createXMLStreamReader(in), in, limits.maxXmlDepth());
        } catch (XMLStreamException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    /**
     * Relationships of {@code sourcePart} ({@code ""} for the package root), keyed by id. External targets are
     * skipped; internal targets are resolved to absolute part names and never escape the package root.
     */
    Map<String, Relationship> relationships(String sourcePart) throws IOException, XMLStreamException {
        int slash = sourcePart.lastIndexOf('/');
        String folder = slash < 0 ? "" : sourcePart.substring(0, slash + 1);
        String relsPart = folder + "_rels/" + sourcePart.substring(slash + 1) + ".rels";
        if (!has(relsPart)) {
            return Map.of();
        }
        Map<String, Relationship> rels = new LinkedHashMap<>();
        try (XmlCursor cursor = open(relsPart)) {
            XMLStreamReader r = cursor.reader();
            while (cursor.hasNext()) {
                if (cursor.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())
                        && !"External".equalsIgnoreCase(r.getAttributeValue(null, "TargetMode"))) {
                    String id = r.getAttributeValue(null, "Id");
                    String target = r.getAttributeValue(null, "Target");
                    Optional<String> resolved = resolve(folder, target);
                    if (id != null && resolved.isPresent()) {
                        rels.put(id, new Relationship(id, r.getAttributeValue(null, "Type"), resolved.get()));
                    }
                }
            }
        }
        return Collections.unmodifiableMap(rels);
    }

    /** Main part named by the root {@code officeDocument} relationship, or {@code fallback}. */
    String mainPart(String fallback) throws IOException, XMLStreamException {
        for (Relationship rel : relationships("").values()) {
            if (rel.typeEndsWith(OFFICE_DOCUMENT) && has(rel.target())) {
                return rel.target();
            }
        }
        return fallback;
    }

    /** Resolves a relationship target against the source folder; rejects paths escaping the package. */
    static Optional<String> resolve(String folder, String target) {
        if (target == null || target.isBlank()) {
            return Optional.empty();
        }
        String path = target.startsWith("/") ? target.substring(1) : folder + target;
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return Optional.empty();
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        return segments.isEmpty() ? Optional.empty() : Optional.of(String.join("/", segments));
    }

    @Override
    public void close() {
        try {
            zip.close();
        } catch (IOException ignored) {
            // read-only archive; nothing to flush
        }
    }

    /** StAX reader that owns its stream and fails once the element depth exceeds the limit. */
    static final class XmlCursor implements AutoCloseable {

        private final XMLStreamReader reader;
        private final InputStream stream;
        private final int maxDepth;
        private int depth;

        XmlCursor(XMLStreamReader reader, InputStream stream, int maxDepth) {
            this.reader = reader;
            this.stream = stream;
            this.maxDepth = maxDepth;
        }

        XMLStreamReader reader() {
            return reader;
        }

        boolean hasNext() throws XMLStreamException {
            return reader.hasNext();
        }

        int next() throws XMLStreamException {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (++depth > maxDepth) {
                    throw new XMLStreamException("XML nesting exceeds " + maxDepth + " levels");
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
            return event;
        }

        /** Skips the subtree of the current start element (cursor ends on its end element). */
        void skipElement() throws XMLStreamException {
            int level = 1;
            while (level > 0 && hasNext()) {
                int event = next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    level++;
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    level--;
                }
            }
        }

        @Override
        public void close() throws IOException {
            try {
                reader.close();
            } catch (XMLStreamException ignored) {
                // the stream is closed below regardless
            } finally {
                stream.close();
            }
        }
    }
}
