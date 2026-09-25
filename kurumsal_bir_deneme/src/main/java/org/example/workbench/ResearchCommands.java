package org.example.workbench;

import org.example.core.DocumentIngestor.IngestResult;
import org.example.core.IngestionException;
import org.example.core.QuotaGate;
import org.example.core.SearchIndex;
import org.example.model.DocumentRecord;
import org.example.model.DocumentType;
import org.example.model.SearchHit;
import org.example.model.SearchResult;
import org.example.model.TextChunk;
import org.example.repl.InternalTerminalEngine;
import org.example.repl.InternalTerminalEngine.CommandSpec;
import org.example.repl.InternalTerminalEngine.Invocation;
import org.example.repl.InternalTerminalEngine.Output;
import org.example.storage.MetadataRecord;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Terminal commands for working with search results and the index's health, layered over a
 * {@link WorkbenchController} without changing how it indexes or ranks:
 *
 * <ul>
 *   <li>reading and citing: {@code show}, {@code cite}, {@code export}</li>
 *   <li>search helpers: {@code near}, {@code similar}, {@code why}, {@code saved}; the scope flags of
 *       {@code find} ({@code --in --type --since --until --tag --pinned --exclude}) are parsed by {@link #filter}</li>
 *   <li>index health: {@code stale}, {@code duplicates}, {@code ocr-needed}</li>
 *   <li>convenience: {@code recent}, {@code pin}/{@code unpin}/{@code pins}, {@code tag}/{@code untag}/{@code tags},
 *       {@code quota}</li>
 * </ul>
 *
 * <p>Rows are the 1-based rows of the current result list; wherever a row is accepted, a document id prefix or a
 * unique file name works too.</p>
 */
final class ResearchCommands {

    static final Set<String> SCOPE_OPTIONS = Set.of("in", "type", "since", "until", "exclude", "tag");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern FORK_SUFFIX = Pattern.compile("\\s\\[[^\\]]*·[^\\]]*\\]$");
    private static final int DEFAULT_NEAR_WINDOW = 10;
    private static final int SIMILAR_TERMS = 8;

    private final WorkbenchController c;

    private ResearchCommands(WorkbenchController controller) {
        this.c = controller;
    }

    static void register(WorkbenchController controller, InternalTerminalEngine t) {
        ResearchCommands r = new ResearchCommands(controller);
        t.register(new CommandSpec("show", "show <row|id> [--context N]",
                "Print a result's passage with N passages before and after (default 1, max 5)",
                Set.of("context"), r::show));
        t.register(new CommandSpec("cite", "cite <row|id>",
                "Copy a citation (file, page/sheet/slide, passage) to the clipboard", Set.of(), r::cite));
        t.register(new CommandSpec("export", "export <query> [--csv|--md] [--top N] [--out FILE] [scope flags]",
                "Write a result list to a CSV (Excel, ';'-separated) or Markdown file", with("top", "out"),
                r::export));
        t.register(new CommandSpec("near", "near <word> <word> [N] [--top N] [scope flags]",
                "Results where both words occur within N words of each other (default 10)", with("top"), r::near));
        t.register(new CommandSpec("similar", "similar <row|id> [--top N]",
                "Documents sharing this one's characteristic words (more like this)", Set.of("top"), r::similar));
        t.register(new CommandSpec("why", "why <row>",
                "Explain a result's rank: matched words, their weights and the score", Set.of(), r::why));
        t.register(new CommandSpec("saved", "saved [<name> | --add <name> | --remove <name> | --list]",
                "Save the last search under a name and run it again later", Set.of("add", "remove"), r::saved));
        t.register(new CommandSpec("stale", "stale [--reindex] [--drop-missing]",
                "Documents whose source changed or was deleted since indexing", Set.of(), r::stale));
        t.register(new CommandSpec("duplicates", "duplicates",
                "Documents indexed in several versions under the same name", Set.of(), r::duplicates));
        t.register(new CommandSpec("ocr-needed", "ocr-needed",
                "PDFs with scanned pages (no text layer) that search cannot see", Set.of(), r::ocrNeeded));
        t.register(new CommandSpec("recent", "recent [--top N]", "Documents opened or previewed recently",
                Set.of("top"), r::recent));
        t.register(new CommandSpec("pin", "pin <row|id>", "Bookmark a document in this project", Set.of(), r::pin));
        t.register(new CommandSpec("unpin", "unpin <row|id>", "Remove a bookmark", Set.of(), r::unpin));
        t.register(new CommandSpec("pins", "pins", "List bookmarked documents", Set.of(), r::pins));
        t.register(new CommandSpec("tag", "tag <row|id> <label>...", "Label a document (find … --tag label)",
                Set.of(), r::tag));
        t.register(new CommandSpec("untag", "untag <row|id> <label>", "Remove a label from a document", Set.of(),
                r::untag));
        t.register(new CommandSpec("tags", "tags [label]", "List labels, or the documents carrying one", Set.of(),
                r::tags));
        t.register(new CommandSpec("quota", "quota", "AI calls left, reset time and allowed hours", Set.of(),
                r::quota));
    }

    private static Set<String> with(String... more) {
        java.util.HashSet<String> all = new java.util.HashSet<>(SCOPE_OPTIONS);
        all.addAll(List.of(more));
        return Set.copyOf(all);
    }

    // ================================================================== targets and scope

    /** A document addressed by row, id prefix or unique name, with the passage the row points at. */
    record Target(DocumentRecord document, int chunkIndex, SearchHit hit) {
    }

    static Target target(WorkbenchController c, String token) {
        String t = token == null ? "" : token.strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("give a result row, a document id or a file name");
        }
        if (t.length() <= 4 && t.chars().allMatch(Character::isDigit)) {
            int row = Integer.parseInt(t) - 1;
            Optional<SearchHit> hit = c.view().hit(row);
            if (hit.isEmpty()) {
                throw new IllegalArgumentException("no result row " + t + " (run a search first)");
            }
            DocumentRecord d = c.document(hit.get().docId())
                    .orElseThrow(() -> new IllegalArgumentException("row " + t + " is no longer indexed"));
            c.select(row);
            return new Target(d, hit.get().chunkIndex(), hit.get());
        }
        Optional<DocumentRecord> byId = c.workbench().resolve(t);
        if (byId.isPresent()) {
            return new Target(byId.get(), 0, null);
        }
        List<DocumentRecord> named = c.documentsNamed(t);
        if (named.size() == 1) {
            return new Target(named.getFirst(), 0, null);
        }
        throw new IllegalArgumentException(named.isEmpty() ? "no result row, document id or file name: " + t
                : named.size() + " documents match '" + t + "'; use the row or the id");
    }

    /**
     * Scope flags shared by {@code find}, {@code near} and {@code export}. {@code --in} takes a document type
     * ({@code pdf}, {@code xlsx}…), a folder path, or a folder name found anywhere in the path; {@code --since} /
     * {@code --until} ({@code yyyy-mm-dd}, inclusive) use the source file's date, else the indexing date;
     * {@code --exclude} takes words separated by commas; {@code -word} inside the query does the same.
     */
    static SearchIndex.Filter filter(WorkbenchController c, Invocation inv) {
        List<Predicate<DocumentRecord>> rules = new ArrayList<>();
        for (String in : values(inv.option("in", null))) {
            rules.add(inScope(in));
        }
        for (String type : values(inv.option("type", null))) {
            DocumentType wanted = DocumentType.fromFileName("x." + type.toLowerCase(Locale.ROOT))
                    .orElseThrow(() -> new IllegalArgumentException("unknown document type '" + type + "'"));
            rules.add(d -> d.type() == wanted);
        }
        LocalDate since = date(inv.option("since", null), "since");
        LocalDate until = date(inv.option("until", null), "until");
        if (since != null || until != null) {
            ZoneId zone = ZoneId.systemDefault();
            rules.add(d -> {
                long modified = c.sourceModifiedMillis(d.sha256());
                LocalDate day = LocalDate.ofInstant(modified > 0 ? Instant.ofEpochMilli(modified) : d.ingestedAt(), zone);
                return (since == null || !day.isBefore(since)) && (until == null || !day.isAfter(until));
            });
        }
        String tag = inv.option("tag", null);
        if (tag != null) {
            Set<String> tagged = c.notes().tagged(tag);
            rules.add(d -> tagged.contains(d.sha256()));
        }
        if (inv.flags().contains("pinned")) {
            rules.add(d -> c.notes().isPinned(d.sha256()));
        }
        Predicate<String> documents = null;
        if (!rules.isEmpty()) {
            Predicate<DocumentRecord> all = rules.stream().reduce(d -> true, Predicate::and);
            documents = sha -> c.document(sha).filter(all).isPresent();
        }
        List<String> excluded = values(inv.option("exclude", null));
        return new SearchIndex.Filter(documents, excluded, List.of());
    }

    /**
     * Documents named by {@code value} for {@code ask --only}: a result row, a document id or unique file name, or
     * else a document type, folder path or folder name as for {@code --in}.
     */
    static Predicate<String> documentsFor(WorkbenchController c, String value) {
        try {
            String sha = target(c, value).document().sha256();
            return sha::equals;
        } catch (IllegalArgumentException notADocument) {
            Predicate<DocumentRecord> in = inScope(value);
            if (c.workbench().documents().stream().noneMatch(in)) {
                throw new IllegalArgumentException("no document, folder or type matches '" + value + "'");
            }
            return sha -> c.document(sha).filter(in).isPresent();
        }
    }

    private static Predicate<DocumentRecord> inScope(String value) {
        Optional<DocumentType> type = DocumentType.fromFileName("x." + value.toLowerCase(Locale.ROOT));
        if (type.isPresent() && !value.contains("/") && !value.contains("\\")) {
            return d -> d.type() == type.get();
        }
        Path folder;
        try {
            folder = Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            folder = null;
        }
        if (folder != null && Files.isDirectory(folder)) {
            Path root = folder;
            return d -> d.source().toAbsolutePath().normalize().startsWith(root);
        }
        String name = value.strip();
        return d -> {
            Path parent = d.source().toAbsolutePath().getParent();
            if (parent == null) {
                return false;
            }
            for (Path segment : parent) {
                if (segment.toString().equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static List<String> values(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    private static LocalDate date(String raw, String option) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("--" + option + " expects a date like 2025-06-30, got '" + raw + "'");
        }
    }

    /** One-line description of the active scope flags, or empty. */
    static String describeScope(Invocation inv) {
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("in", "type", "since", "until", "tag", "exclude")) {
            String v = inv.option(key, null);
            if (v != null) {
                sb.append(sb.isEmpty() ? "" : ", ").append(key).append(' ').append(v);
            }
        }
        if (inv.flags().contains("pinned")) {
            sb.append(sb.isEmpty() ? "" : ", ").append("pinned");
        }
        return sb.toString();
    }

    /** Page coordinate in the words a reader of that format uses. */
    static String location(DocumentType type, int page) {
        if (page <= 0) {
            return "";
        }
        return switch (type) {
            case PPT, PPTX -> "slayt " + page;
            case XLSX, XLS -> "çalışma sayfası " + page;
            default -> "s. " + page;
        };
    }

    // ================================================================== reading and citing

    private void show(Invocation inv, Output out) {
        Target t = target(c, inv.joinedArgs());
        int context = inv.intOption("context", 1, 0, 5);
        DocumentRecord d = t.document();
        int from = Math.max(0, t.chunkIndex() - context);
        int to = Math.min(d.chunks().size() - 1, t.chunkIndex() + context);
        out.printf("── %s (%s) · passages %d-%d of %d ──", d.fileName(), d.shortId(), from, to, d.chunks().size());
        for (int i = from; i <= to; i++) {
            TextChunk chunk = d.chunks().get(i);
            String where = location(d.type(), chunk.page());
            out.println((i == t.chunkIndex() ? "▶ " : "  ") + "#" + i + (where.isEmpty() ? "" : " · " + where));
            out.println(chunk.text().strip());
            out.println();
        }
    }

    private void cite(Invocation inv, Output out) {
        Target t = target(c, inv.joinedArgs());
        DocumentRecord d = t.document();
        int page = t.hit() != null ? t.hit().page()
                : d.chunks().isEmpty() ? -1 : d.chunks().get(Math.min(t.chunkIndex(), d.chunks().size() - 1)).page();
        String where = location(d.type(), page);
        String citation = d.fileName() + (where.isEmpty() ? "" : ", " + where) + ", parça #" + t.chunkIndex()
                + " [" + d.shortId() + "]";
        Optional<org.example.platform.ClipboardPort> clipboard = c.clipboard();
        clipboard.ifPresent(cb -> cb.putText(citation));
        out.println(citation + (clipboard.isPresent() ? "   (copied to the clipboard)" : ""));
    }

    private void export(Invocation inv, Output out) throws IOException {
        String query = inv.joinedArgs();
        if (query.isBlank()) {
            throw new IllegalArgumentException("usage: export <query> [--csv|--md] [--top N] [--out FILE]");
        }
        boolean markdown = inv.flags().contains("md");
        int top = inv.intOption("top", 50, 1, 200);
        SearchResult result = c.workbench().find(query, top, filter(c, inv));
        Path file = inv.options().containsKey("out") ? Path.of(inv.option("out", "")).toAbsolutePath()
                : c.dataDir().resolve("exports").resolve(slug(query) + "-"
                + LocalDateTime.now().format(FILE_STAMP) + (markdown ? ".md" : ".csv"));
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            if (markdown) {
                w.write("# Arama: " + query + "\n\n");
                w.write("| # | Dosya | Konum | Parça | Skor | Alıntı |\n|---|---|---|---|---|---|\n");
            } else {
                w.write('﻿'); // lets Excel detect UTF-8 (Turkish letters)
                w.write("sira;dosya;konum;parca;skor;yol;alinti\r\n");
            }
            int rank = 1;
            for (SearchHit h : result.hits()) {
                DocumentRecord d = c.document(h.docId()).orElse(null);
                String where = d == null ? "" : location(d.type(), h.page());
                String path = d == null ? "" : d.source().toAbsolutePath().toString();
                String snippet = h.snippet().replaceAll("\\s+", " ").strip();
                String score = String.format(Locale.ROOT, "%.3f", h.score());
                if (markdown) {
                    w.write("| " + rank + " | " + md(h.fileName()) + " | " + md(where) + " | " + h.chunkIndex()
                            + " | " + score + " | " + md(snippet) + " |\n");
                } else {
                    w.write(String.join(";", String.valueOf(rank), csv(h.fileName()), csv(where),
                            String.valueOf(h.chunkIndex()), score, csv(path), csv(snippet)) + "\r\n");
                }
                rank++;
            }
        }
        out.printf("exported %d result(s) to %s", result.hits().size(), file);
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String md(String value) {
        return value.replace("|", "\\|");
    }

    private static String slug(String query) {
        String s = org.example.project.ProjectProfile.slug(query);
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    // ================================================================== search helpers

    private void near(Invocation inv, Output out) {
        List<String> args = inv.args();
        if (args.size() < 2) {
            throw new IllegalArgumentException("usage: near <word> <word> [N]");
        }
        int window = DEFAULT_NEAR_WINDOW;
        if (args.size() >= 3 && args.get(2).chars().allMatch(Character::isDigit)) {
            window = Math.max(1, Math.min(50, Integer.parseInt(args.get(2))));
        }
        SearchIndex.Filter scope = filter(c, inv);
        SearchIndex.Filter filter = new SearchIndex.Filter(scope.documents(), scope.excluded(),
                List.of(new SearchIndex.Proximity(args.get(0), args.get(1), window)));
        SearchResult result = c.search(args.get(0) + " " + args.get(1), inv.intOption("top", 20, 1, 200), filter);
        out.printf("'%s' and '%s' within %d words:", args.get(0), args.get(1), window);
        c.printResults(result, out);
    }

    private void similar(Invocation inv, Output out) {
        Target t = target(c, inv.joinedArgs());
        String self = t.document().sha256();
        List<String> terms = c.workbench().index().characteristicTerms(self, SIMILAR_TERMS);
        if (terms.isEmpty()) {
            out.println(t.document().fileName() + " shares no distinctive words with other documents");
            return;
        }
        SearchIndex.Filter others = SearchIndex.Filter.NONE.withDocuments(sha -> !sha.equals(self)
                && c.document(sha).filter(DocumentRecord::isSystemDocument).isEmpty());
        SearchResult result = c.search(String.join(" ", terms), inv.intOption("top", 10, 1, 200), others);
        out.println("like " + t.document().fileName() + " (" + String.join(", ", terms) + "):");
        c.printResults(result, out);
    }

    private void why(Invocation inv, Output out) {
        String token = inv.joinedArgs().strip();
        if (token.isEmpty() || token.length() > 4 || !token.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("usage: why <row> (a row of the current result list)");
        }
        Target t = target(c, token);
        String query = c.view().query();
        SearchIndex.Explanation e = c.workbench().index().explain(query, t.document().sha256(), t.chunkIndex())
                .orElseThrow(() -> new IllegalArgumentException("row " + token + " does not match '" + query + "'"));
        String where = location(t.document().type(), t.hit().page());
        out.printf("row %s: %s%s · passage #%d · score %.3f  (query: %s)", token, t.document().fileName(),
                where.isEmpty() ? "" : " · " + where, t.chunkIndex(), t.hit().score(), query);
        out.printf("  %-28s %6s %4s %6s %6s %8s", "word (query -> index)", "weight", "tf", "df", "idf", "points");
        for (SearchIndex.TermWeight w : e.terms()) {
            String word = w.queryTerm().equals(w.indexTerm()) ? w.indexTerm() : w.queryTerm() + " -> " + w.indexTerm();
            out.printf("  %-28s %6.2f %4d %6d %6.2f %8.3f", word, w.variantWeight(), w.termFrequency(),
                    w.documentFrequency(), w.idf(), w.contribution());
        }
        out.printf("  %d of %d query word(s) matched: x%.2f%s", e.matchedQueryTerms(), e.queryTerms(),
                e.coordination(), e.phraseBoost() > 1 ? String.format(Locale.ROOT, "; exact phrase: x%.2f", e.phraseBoost()) : "");
        out.printf("  passage length %d words (index average %.0f); shorter passages weigh each match more",
                e.chunkTokens(), e.averageChunkTokens());
        out.println("  weight 0.60 = a suffixed form (kira -> kiracı); df = passages containing the word; rare words"
                + " (high idf) count most");
    }

    private void saved(Invocation inv, Output out) throws IOException {
        ProjectNotes notes = c.notes();
        if (inv.options().containsKey("add")) {
            String line = c.lastSearchLine().orElseThrow(
                    () -> new IllegalArgumentException("run a search first (find …), then save it"));
            notes.saveSearch(inv.option("add", ""), line);
            out.println("saved '" + ProjectNotes.searchName(inv.option("add", "")) + "': find " + line);
        } else if (inv.options().containsKey("remove")) {
            out.println(notes.removeSearch(inv.option("remove", "")) ? "removed" : "no such saved search");
        } else if (!inv.args().isEmpty()) {
            String name = inv.args().getFirst();
            String line = notes.savedSearch(name)
                    .orElseThrow(() -> new IllegalArgumentException("no saved search '" + name + "' (saved --list)"));
            c.terminal().execute("search " + line);
        } else {
            Map<String, String> all = notes.savedSearches();
            all.forEach((name, line) -> out.printf("  %-20s find %s", name, line));
            out.println(all.size() + " saved search(es)" + (all.isEmpty() ? "; save one with 'saved --add NAME'" : ""));
        }
    }

    // ================================================================== index health

    private void stale(Invocation inv, Output out) {
        List<DocumentRecord> changed = new ArrayList<>();
        List<DocumentRecord> missing = new ArrayList<>();
        List<DocumentRecord> unreachable = new ArrayList<>();
        for (DocumentRecord d : c.workbench().documents()) {
            if (!d.local()) {
                continue;
            }
            MetadataRecord meta = c.metadata(d);
            if (meta.sourceModifiedMillis() < 0) {
                continue;
            }
            switch (meta.freshness()) {
                case CHANGED -> changed.add(d);
                case MISSING -> missing.add(d);
                case UNKNOWN -> unreachable.add(d);
                case FRESH -> {
                }
            }
        }
        list(out, "changed since indexing", changed);
        list(out, "deleted from disk", missing);
        list(out, "not reachable right now (drive or share offline?)", unreachable);
        if (changed.isEmpty() && missing.isEmpty() && unreachable.isEmpty()) {
            out.println("all indexed sources are up to date");
            return;
        }
        if (inv.flags().contains("reindex")) {
            int done = 0;
            for (DocumentRecord d : changed) {
                try {
                    if (c.reindex(d.sha256(), d.source()) instanceof IngestResult.Ingested) {
                        done++;
                    }
                } catch (IngestionException | RuntimeException e) {
                    out.println("  ! " + d.fileName() + ": " + e.getMessage() + " (previous version kept)");
                }
            }
            out.println("re-indexed " + done + "/" + changed.size());
        }
        if (inv.flags().contains("drop-missing")) {
            missing.forEach(d -> c.untrack(d.sha256()));
            out.println("removed " + missing.size() + " deleted document(s) from the index");
        }
        if (!inv.flags().contains("reindex") && !inv.flags().contains("drop-missing")) {
            out.println("stale --reindex re-reads changed files; stale --drop-missing removes deleted ones");
        }
    }

    private static void list(Output out, String title, List<DocumentRecord> docs) {
        if (docs.isEmpty()) {
            return;
        }
        out.println(docs.size() + " " + title + ":");
        docs.forEach(d -> out.printf("  %s  %s", d.shortId(), d.source()));
    }

    private void duplicates(Invocation inv, Output out) {
        Map<String, List<DocumentRecord>> byName = new TreeMap<>();
        for (DocumentRecord d : c.workbench().documents()) {
            String base = FORK_SUFFIX.matcher(d.fileName()).replaceFirst("").toLowerCase(Locale.ROOT);
            byName.computeIfAbsent(base, k -> new ArrayList<>()).add(d);
        }
        int groups = 0;
        for (Map.Entry<String, List<DocumentRecord>> e : byName.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            groups++;
            out.println(e.getKey() + " — " + e.getValue().size() + " versions:");
            e.getValue().stream().sorted(Comparator.comparing(DocumentRecord::ingestedAt)).forEach(d ->
                    out.printf("  %s  %9s  indexed %s  %-8s %s", d.shortId(), human(d.sizeBytes()),
                            STAMP.format(LocalDateTime.ofInstant(d.ingestedAt(), ZoneId.systemDefault())),
                            d.origin(), d.source()));
        }
        out.println(groups == 0 ? "no file name is indexed in more than one version"
                : groups + " name(s) with several versions (identical content is never indexed twice)");
    }

    private void ocrNeeded(Invocation inv, Output out) {
        List<DocumentRecord> partial = c.workbench().documents().stream()
                .filter(d -> d.emptyPages() > 0 && d.pageCount() > 0)
                .sorted(Comparator.comparingDouble((DocumentRecord d) -> (double) d.emptyPages() / d.pageCount())
                        .reversed())
                .toList();
        for (DocumentRecord d : partial) {
            out.printf("  %s  %d of %d page(s) without text  %s", d.shortId(), d.emptyPages(), d.pageCount(),
                    d.source());
        }
        Set<Path> refused = c.scannedOnlyRefusals();
        for (Path p : refused) {
            out.println("  (not indexed)  every page scanned  " + p);
        }
        out.println(partial.isEmpty() && refused.isEmpty() ? "no scanned PDF pages found"
                : "these pages are images: search cannot see them until the PDFs are run through OCR elsewhere");
    }

    // ================================================================== convenience

    private void recent(Invocation inv, Output out) {
        List<ProjectNotes.Recent> recent = c.notes().recent();
        int top = inv.intOption("top", 15, 1, ProjectNotes.MAX_RECENT);
        recent.stream().limit(top).forEach(r -> out.printf("  %s  %-7s %s%s",
                STAMP.format(LocalDateTime.ofInstant(r.at(), ZoneId.systemDefault())), r.action(),
                r.path().getFileName(), Files.isRegularFile(r.path()) ? "  " + r.path().getParent() : "  (gone)"));
        if (recent.isEmpty()) {
            out.println("nothing opened or previewed yet");
        }
    }

    private void pin(Invocation inv, Output out) throws IOException {
        Target t = target(c, inv.joinedArgs());
        out.println((c.notes().pin(t.document().sha256()) ? "pinned " : "already pinned ") + t.document().fileName());
    }

    private void unpin(Invocation inv, Output out) throws IOException {
        Target t = target(c, inv.joinedArgs());
        out.println((c.notes().unpin(t.document().sha256()) ? "unpinned " : "was not pinned: ")
                + t.document().fileName());
    }

    private void pins(Invocation inv, Output out) {
        List<String> pinned = c.notes().pinned();
        for (String sha : pinned) {
            out.println(c.document(sha).map(d -> "  " + d.shortId() + "  " + d.fileName() + "  " + d.source())
                    .orElse("  " + sha.substring(0, 12) + "  (not in the index this session)"));
        }
        out.println(pinned.size() + " pinned document(s)" + (pinned.isEmpty() ? "; pin one with 'pin <row>'" : ""));
    }

    private void tag(Invocation inv, Output out) throws IOException {
        if (inv.args().size() < 2) {
            throw new IllegalArgumentException("usage: tag <row|id> <label>...");
        }
        Target t = target(c, inv.args().getFirst());
        List<String> added = new ArrayList<>();
        for (String label : inv.args().subList(1, inv.args().size())) {
            if (c.notes().tag(t.document().sha256(), label)) {
                added.add(ProjectNotes.label(label));
            }
        }
        out.println(t.document().fileName() + ": " + String.join(", ", c.notes().tags(t.document().sha256()))
                + (added.isEmpty() ? " (unchanged)" : ""));
    }

    private void untag(Invocation inv, Output out) throws IOException {
        if (inv.args().size() < 2) {
            throw new IllegalArgumentException("usage: untag <row|id> <label>");
        }
        Target t = target(c, inv.args().getFirst());
        boolean removed = c.notes().untag(t.document().sha256(), inv.args().get(1));
        out.println(removed ? "removed" : "the document does not carry that label");
    }

    private void tags(Invocation inv, Output out) {
        if (inv.args().isEmpty()) {
            Map<String, Integer> labels = c.notes().labels();
            labels.forEach((label, count) -> out.printf("  %-24s %d document(s)", label, count));
            out.println(labels.size() + " label(s)" + (labels.isEmpty() ? "; add one with 'tag <row> <label>'" : ""));
            return;
        }
        Set<String> tagged = c.notes().tagged(inv.args().getFirst());
        Map<String, String> lines = new LinkedHashMap<>();
        for (String sha : tagged) {
            lines.put(sha, c.document(sha).map(d -> "  " + d.shortId() + "  " + d.fileName() + "  " + d.source())
                    .orElse("  " + sha.substring(0, 12) + "  (not in the index this session)"));
        }
        lines.values().forEach(out::println);
        out.println(tagged.size() + " document(s); search inside them with: find <words> --tag "
                + ProjectNotes.label(inv.args().getFirst()));
    }

    private void quota(Invocation inv, Output out) {
        QuotaGate.Snapshot q = c.workbench().quota().snapshot();
        boolean unlimited = q.limit() == Integer.MAX_VALUE;
        out.println(unlimited ? "AI quota: unlimited"
                : String.format(Locale.ROOT, "AI quota: %d of %d left%s · %s%s", q.remaining(), q.limit(),
                q.resetAt() == null ? "" : " · next call frees up "
                        + STAMP.format(LocalDateTime.ofInstant(q.resetAt(), ZoneId.systemDefault())),
                q.schedule(), q.open() ? "" : " · closed now (outside allowed hours)"));
    }

    private static String human(long bytes) {
        return bytes < 1024 ? bytes + " B" : bytes < 1 << 20 ? (bytes >> 10) + " KB"
                : String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
