package org.example.ui;

import org.example.model.BinaryAsset;
import org.example.model.DocumentRecord;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable folder view over the indexed documents, derived from their source paths only: the explorer never scans
 * the disk, so it lists exactly what the index holds and costs one pass over the document list to build.
 *
 * <p>Workspace roots are the top-most folders that hold indexed documents: the parent folders of all documents plus
 * any project watch root that contains some of them, reduced to the ones with no candidate above them. A document in
 * {@code C:\Kurs\CMP3005\hafta4\a.pdf} and one in {@code C:\Kurs\CMP3005\b.pdf} therefore share the root
 * {@code C:\Kurs\CMP3005}, which the explorer then opens as {@code CMP3005 > hafta4}.</p>
 *
 * <p>Binary assets (videos, photos, archives registered for sharing) are placed the same way, by the folder of their
 * file. They count towards a folder's size and latest date and have their own {@link Folder#assets()} count, but
 * never towards documents or chunks: they are not in the search index.</p>
 */
final class FolderIndex {

    /** One folder with recursive totals (all documents and binary assets at or below it). */
    record Folder(Path path, String name, int documents, long chunks, long bytes, Instant latest, int assets) {
    }

    static final FolderIndex EMPTY = build(List.of(), List.of(), List.of());

    private final List<Folder> roots;
    private final Map<Path, Folder> folders;
    private final Map<Path, List<Path>> children;
    private final Map<Path, List<DocumentRecord>> documentsIn;
    private final Map<Path, List<BinaryAsset>> assetsIn;
    private final Map<Path, Path> rootOf;
    private final List<DocumentRecord> recent;
    private final List<BinaryAsset> recentAssets;
    private final long chunkTotal;

    private FolderIndex(List<Folder> roots, Map<Path, Folder> folders, Map<Path, List<Path>> children,
                        Map<Path, List<DocumentRecord>> documentsIn, Map<Path, List<BinaryAsset>> assetsIn,
                        Map<Path, Path> rootOf, List<DocumentRecord> recent, List<BinaryAsset> recentAssets,
                        long chunkTotal) {
        this.roots = roots;
        this.folders = folders;
        this.children = children;
        this.documentsIn = documentsIn;
        this.assetsIn = assetsIn;
        this.rootOf = rootOf;
        this.recent = recent;
        this.recentAssets = recentAssets;
        this.chunkTotal = chunkTotal;
    }

    static FolderIndex build(Collection<DocumentRecord> documents, Collection<Path> watchRoots) {
        return build(documents, List.of(), watchRoots);
    }

    static FolderIndex build(Collection<DocumentRecord> documents, Collection<BinaryAsset> binaryAssets,
                             Collection<Path> watchRoots) {
        List<DocumentRecord> docs = List.copyOf(documents);
        List<BinaryAsset> assets = List.copyOf(binaryAssets);
        List<Path> parents = new ArrayList<>(docs.size());
        List<Path> assetParents = new ArrayList<>(assets.size());
        Set<Path> candidates = new HashSet<>();
        for (DocumentRecord d : docs) {
            Path parent = parentOf(d.source());
            parents.add(parent);
            candidates.add(parent);
        }
        for (BinaryAsset a : assets) {
            Path parent = parentOf(a.source());
            assetParents.add(parent);
            candidates.add(parent);
        }
        // A watch root is only a candidate when it actually holds documents (an empty added folder is no root).
        for (Path w : watchRoots) {
            Path root = w.toAbsolutePath().normalize();
            if (candidates.stream().anyMatch(p -> p.startsWith(root))) {
                candidates.add(root);
            }
        }

        Map<Path, Path> rootOf = new HashMap<>();
        Map<Path, List<DocumentRecord>> documentsIn = new HashMap<>();
        Map<Path, Set<Path>> childSets = new HashMap<>();
        Map<Path, List<BinaryAsset>> assetsIn = new HashMap<>();
        Map<Path, long[]> totals = new HashMap<>();      // documents, chunks, bytes, assets
        Map<Path, Instant> latest = new HashMap<>();
        Set<Path> rootSet = new LinkedHashSet<>();
        long chunkTotal = 0;
        for (int i = 0; i < docs.size(); i++) {
            DocumentRecord d = docs.get(i);
            Path parent = parents.get(i);
            Path root = rootOf.computeIfAbsent(parent, p -> topmostCandidate(p, candidates));
            rootSet.add(root);
            documentsIn.computeIfAbsent(parent, p -> new ArrayList<>()).add(d);
            chunkTotal += d.chunks().size();
            // Walk up to the root once per document; every folder on the way gets the document in its totals.
            for (Path f = parent; f != null; f = f.getParent()) {
                long[] t = totals.computeIfAbsent(f, p -> new long[4]);
                t[0]++;
                t[1] += d.chunks().size();
                t[2] += d.sizeBytes();
                latest.merge(f, d.ingestedAt(), (a, b) -> a.isAfter(b) ? a : b);
                rootOf.putIfAbsent(f, root);
                if (f.equals(root)) {
                    break;
                }
                childSets.computeIfAbsent(f.getParent(), p -> new HashSet<>()).add(f);
            }
        }
        for (int i = 0; i < assets.size(); i++) {
            BinaryAsset a = assets.get(i);
            Path parent = assetParents.get(i);
            Path root = rootOf.computeIfAbsent(parent, p -> topmostCandidate(p, candidates));
            rootSet.add(root);
            assetsIn.computeIfAbsent(parent, p -> new ArrayList<>()).add(a);
            for (Path f = parent; f != null; f = f.getParent()) {
                long[] t = totals.computeIfAbsent(f, p -> new long[4]);
                t[2] += a.sizeBytes();
                t[3]++;
                latest.merge(f, a.registeredAt(), (x, y) -> x.isAfter(y) ? x : y);
                rootOf.putIfAbsent(f, root);
                if (f.equals(root)) {
                    break;
                }
                childSets.computeIfAbsent(f.getParent(), p -> new HashSet<>()).add(f);
            }
        }

        Map<Path, Folder> folders = new HashMap<>();
        totals.forEach((path, t) -> folders.put(path,
                new Folder(path, displayName(path), (int) t[0], t[1], t[2], latest.get(path), (int) t[3])));
        Map<Path, List<Path>> children = new HashMap<>();
        childSets.forEach((parent, set) -> {
            List<Path> sorted = new ArrayList<>(set);
            sorted.sort(Comparator.comparing(p -> displayName(p).toLowerCase(Locale.ROOT)));
            children.put(parent, List.copyOf(sorted));
        });
        documentsIn.replaceAll((p, list) -> List.copyOf(list));
        assetsIn.replaceAll((p, list) -> List.copyOf(list));

        List<Folder> roots = new ArrayList<>();
        for (Path r : rootSet) {
            roots.add(folders.get(r));
        }
        roots.sort(Comparator.comparing((Folder f) -> f.name().toLowerCase(Locale.ROOT)).thenComparing(Folder::path));

        List<DocumentRecord> recent = new ArrayList<>(docs);
        recent.sort(Comparator.comparing(DocumentRecord::ingestedAt).reversed());
        List<BinaryAsset> recentAssets = new ArrayList<>(assets);
        recentAssets.sort(Comparator.comparing(BinaryAsset::registeredAt).reversed());
        return new FolderIndex(List.copyOf(roots), Map.copyOf(folders), Map.copyOf(children), Map.copyOf(documentsIn),
                Map.copyOf(assetsIn), Map.copyOf(rootOf), List.copyOf(recent), List.copyOf(recentAssets), chunkTotal);
    }

    private static Path parentOf(Path source) {
        Path abs = source.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        return parent == null ? abs : parent;
    }

    private static Path topmostCandidate(Path folder, Set<Path> candidates) {
        Path top = folder;
        for (Path f = folder.getParent(); f != null; f = f.getParent()) {
            if (candidates.contains(f)) {
                top = f;
            }
        }
        return top;
    }

    /** Last path element, or the whole path for a drive root such as {@code C:\}. */
    static String displayName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    // ================================================================== queries

    List<Folder> roots() {
        return roots;
    }

    Optional<Folder> folder(Path path) {
        return path == null ? Optional.empty() : Optional.ofNullable(folders.get(path));
    }

    List<Folder> subfolders(Path path) {
        List<Path> paths = children.getOrDefault(path, List.of());
        List<Folder> out = new ArrayList<>(paths.size());
        for (Path p : paths) {
            out.add(folders.get(p));
        }
        return out;
    }

    /** Documents directly inside {@code path} (not in its subfolders). */
    List<DocumentRecord> documents(Path path) {
        return documentsIn.getOrDefault(path, List.of());
    }

    /** Binary assets directly inside {@code path} (not in its subfolders). */
    List<BinaryAsset> assets(Path path) {
        return assetsIn.getOrDefault(path, List.of());
    }

    /** Every binary asset, newest registration first. */
    List<BinaryAsset> recentAssets() {
        return recentAssets;
    }

    int assetCount() {
        return recentAssets.size();
    }

    /** The workspace root {@code path} belongs to, if it is a folder of this index. */
    Optional<Path> rootOf(Path path) {
        return path == null ? Optional.empty() : Optional.ofNullable(rootOf.get(path));
    }

    boolean isRoot(Path path) {
        return path != null && path.equals(rootOf.get(path));
    }

    /** Folders from the root down to {@code path}, both included; empty when {@code path} is not indexed. */
    List<Folder> trail(Path path) {
        Path root = rootOf.get(path);
        if (root == null) {
            return List.of();
        }
        List<Folder> trail = new ArrayList<>();
        for (Path f = path; f != null; f = f.getParent()) {
            trail.addFirst(folders.get(f));
            if (f.equals(root)) {
                break;
            }
        }
        return trail;
    }

    /** Every document, newest ingestion first. */
    List<DocumentRecord> recent() {
        return recent;
    }

    int documentCount() {
        return recent.size();
    }

    long chunkCount() {
        return chunkTotal;
    }
}
