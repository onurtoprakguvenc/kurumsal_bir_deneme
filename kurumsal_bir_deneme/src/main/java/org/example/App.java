package org.example;

import org.example.admin.CentralPolicy;
import org.example.core.AnswerModel.AnswerException;
import org.example.core.DocumentIngestor.IngestResult;
import org.example.core.EphemeralContext;
import org.example.core.QuotaGate;
import org.example.core.IngestionException;
import org.example.core.SearchIndex;
import org.example.core.Workbench;
import org.example.index.InvertedIndex;
import org.example.ingest.DocumentParser;
import org.example.llm.GeminiStreamEngine;
import org.example.llm.ModelProfile;
import org.example.model.BinaryAsset;
import org.example.model.ContentKind;
import org.example.model.DocumentRecord;
import org.example.model.Location;
import org.example.model.SearchHit;
import org.example.model.SearchResult;
import org.example.p2p.ContentStore;
import org.example.p2p.ContentStore.StoredFile;
import org.example.p2p.AccessList;
import org.example.p2p.DeviceIdentity;
import org.example.p2p.DeviceRole;
import org.example.p2p.FileTransferService;
import org.example.p2p.FileTransferService.TransferResult;
import org.example.p2p.LanSecurity;
import org.example.p2p.LanSyncService;
import org.example.p2p.MachineKey;
import org.example.p2p.PeerDiscovery;
import org.example.p2p.PeerInfo;
import org.example.p2p.TransferMeter;
import org.example.p2p.TrustStore;
import org.example.p2p.ZeroTrust;
import org.example.quota.QuotaManager;
import org.example.util.Hashing;
import org.example.watcher.FileWatcherService;
import org.example.workspace.WorkspaceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Headless console runner for the document workbench node.
 *
 * <pre>
 * java -cp app.jar:pdfbox-app-3.x.jar org.example.App [options]            interactive console (stdin)
 * java -cp ... org.example.App [options] -c "index ./docs" -c "find kira"  one-shot script
 *
 * Options: --store DIR  --name NAME  --model ID  --discovery-port N  --group ADDR  --transfer-port N
 *          --seed HOST:PORT (repeatable)  --no-p2p  --no-autoload  --api-base URL  --verbose  --help
 * Env:     GEMINI_API_KEY (answers), DWB_TRUST (zero|legacy LAN mode), DWB_SECRET (legacy LAN pre-shared key),
 *          DWB_HOME (store), DWB_MODEL,
 *           DWB_AI_QUOTA / DWB_AI_RATE / DWB_AI_HOURS / DWB_AI_DAYS (AI quota shield)
 * </pre>
 */
public final class App implements AutoCloseable {

    private static final String HELP = """
            Commands
              index <file|dir>...          ingest PDF/DOCX/XLSX/PPTX/DOC/XLS/PPT/CSV/TXT into the RAM index (dirs);
                                           a media/archive file (MP4, MKV, JPG, ZIP, …) is registered for sharing only
              find <query> [-k N]          keyword search; "exact phrase", prefix*, suffixed forms auto-match
              lookup <term>                raw term → locations (docId, chunk, position, offset)
              docs                         list indexed documents
              assets                       list binary content (media, archives) held for sharing, not indexed
              open <hash|name>             open an indexed document or media file in its native OS application
              status                       index, workspace, model, AI quota and LAN status
              stats                        index statistics
              remove <hash>                untrack a document (index, catalog, sharing) - file stays on disk
              delete <hash> --purge-disk   delete the physical file after a y/N confirmation
              watch [on|off]               auto-reindex indexed files when they change on disk
              peers                        LAN nodes with their advertised documents
              peers --trust <name|id>      legacy mode: accept pushes only from trusted nodes (--untrust, --trusted)
              devices                      zero-trust: this device's fingerprint, trusted devices (role, department)
              devices --pending            unpaired devices seen on the network (isolated until paired)
              devices --remove <device>    stop trusting a device (also drops it from every access list)
              devices --role <device> FULL_PEER|RESTRICTED_GUEST · devices --dept <device> <NAME|->
              pair --new [--guest] [--dept NAME]   open a one-time 6-digit PIN here (5 minutes, one attempt)
              pair <peer|host:port> <PIN>  pair this device with one showing a PIN; compare the verification codes
              acl [<hash|name>] [--grant <device|dept:NAME>] [--revoke <device|dept:NAME>]
                                           per-document access list (guests get one-shot grants)
              share [--list]               which indexed documents peers can see; share off|on <hash|name>
              send <hash|path> <peer>      push a document or media file to one peer (name, id prefix or host:port)
              fetch <hash> [peer]          pull a document (hash prefix of an advertised doc, or full hash + peer)
              ask [--model ID] <question>  grounded answer from the top keyword hits (streams to stdout)
              model [ID] | models          show/switch the Gemini model | list models
              reset                        forget the ephemeral 3-turn context
              wait <seconds>               pause (useful in -c scripts while peers are discovered)
              help | quit
            """;

    private static final int MAX_WAIT_SECONDS = 3_600;

    private final Options options;
    private final CentralPolicy policy;
    private final PrintStream out;
    private final PrintStream err;
    private final boolean ansi;
    private final ContentStore store;
    private final WorkspaceManager workspace = new WorkspaceManager();
    private final QuotaManager quota;
    private final Workbench workbench;
    private final DocumentParser parser = new DocumentParser();
    private final GeminiStreamEngine engine;
    private final LanSecurity security;
    /** Zero-trust context (the default LAN mode); {@code null} in legacy mode or without LAN. */
    private final ZeroTrust trust;
    private final BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private FileTransferService transfer;
    private PeerDiscovery discovery;
    private FileWatcherService watcher;
    private ModelProfile selectedProfile;

    // ================================================================== bootstrap

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%4$s] %5$s%6$s%n");
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("run with --help for usage");
            System.exit(2);
            return;
        }
        if (options.help) {
            System.out.println(usage());
            return;
        }
        if (options.hashPassphrase) {
            System.exit(hashPassphrase());
            return;
        }
        configureLogging(options.verbose);
        App app;
        try {
            app = new App(options);
            app.start();
        } catch (IOException | RuntimeException e) {
            System.err.println("startup failed: " + e.getMessage());
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(app::close));
        int status = options.commands.isEmpty() ? app.repl() : app.script(options.commands);
        app.close();
        System.exit(status);
    }

    /**
     * {@code --hash-passphrase}: asks for a passphrase twice (hidden when a console is attached, otherwise one line
     * from stdin) and prints the {@code admin.passphrase.hash=…} line for the central policy. Nothing is stored.
     */
    private static int hashPassphrase() {
        char[] first;
        char[] second;
        java.io.Console console = System.console();
        try {
            if (console != null) {
                first = console.readPassword("New admin passphrase (min. 8 characters): ");
                second = console.readPassword("Repeat: ");
            } else {
                String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                first = line == null ? new char[0] : line.toCharArray();
                second = first.clone();
            }
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
        if (first == null || second == null || !java.util.Arrays.equals(first, second)) {
            System.err.println("error: the passphrases do not match");
            return 1;
        }
        try {
            System.out.println("admin.passphrase.hash=" + org.example.admin.AdminControlEngine.hashPassphrase(first));
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        } finally {
            java.util.Arrays.fill(first, '\0');
            java.util.Arrays.fill(second, '\0');
        }
    }

    App(Options options) throws IOException {
        this.options = options;
        this.out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        this.err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        this.ansi = System.console() != null && System.getenv("NO_COLOR") == null
                && !"dumb".equals(System.getenv("TERM"));
        this.store = new ContentStore(options.store);
        this.security = LanSecurity.fromSecret(System.getenv("DWB_SECRET"));
        this.selectedProfile = options.model;
        this.policy = CentralPolicy.loadDefault();
        policy.problems().forEach(p -> err.println("! policy: " + p));
        if (policy.present() && policy.writableByUser()) {
            err.println("! policy: " + policy.file() + " is writable by this user; its settings are not enforced");
        }
        if (options.p2p && !policy.lanAllowed()) {
            err.println("LAN sharing is turned off by the central IT policy.");
            options.p2p = false;
        }
        ZeroTrust zeroTrust = null;
        if (options.p2p && !LanSyncService.Settings.legacyTrust(policy.environment(System::getenv))) {
            try {
                zeroTrust = ZeroTrust.load(store.root(), MachineKey.detect()).withLocalName(options.name);
                zeroTrust.identity().notice().ifPresent(n -> err.println("! " + n));
                if (security.enabled()) {
                    err.println("note: DWB_SECRET is only used in legacy mode (DWB_TRUST=legacy); zero-trust mode"
                            + " authenticates devices by their keys");
                }
            } catch (IOException e) {
                err.println("LAN disabled: zero-trust device identity unavailable (" + e.getMessage() + ")");
                options.p2p = false;
            }
        }
        this.trust = zeroTrust;
        if (options.p2p && policy.lanRequiresSecret() && trust == null && !security.enabled()) {
            err.println("LAN sharing needs authenticated peers by central IT policy (zero-trust mode, or DWB_SECRET in"
                    + " legacy mode); starting without LAN.");
            options.p2p = false;
        }

        String apiKey = Optional.ofNullable(System.getProperty("gemini.api.key")).orElse(System.getenv("GEMINI_API_KEY"));
        this.engine = !policy.aiAllowed() || apiKey == null || apiKey.isBlank()
                ? null
                : new GeminiStreamEngine(apiKey, options.model, options.apiBase, Duration.ofSeconds(60));
        this.quota = new QuotaManager(QuotaManager.Config.fromEnvironment(policy.environment(System::getenv)),
                store.root().resolve("ai-quota.state"));
        SearchIndex index = new InvertedIndex();
        this.workbench = new Workbench(parser, index, engine, quota, Workbench.DEFAULT_MIN_SCORE);
    }

    void start() throws IOException {
        // Binary files registered in earlier sessions (persisted by the store) are openable and listed again.
        store.registeredAssets().forEach(workspace::register);
        if (options.autoload) {
            int loaded = 0;
            int blobs = 0;
            for (StoredFile file : store.objects()) {
                if (DocumentParser.classify(file.path(), file.name()) == ContentKind.BINARY) {
                    blobs++; // media and archives are served from the store as they are; nothing to index
                    workspace.register(receivedAsset(file, DocumentRecord.LOCAL));
                    continue;
                }
                try {
                    if (workbench.ingest(file.path(), file.name()) instanceof IngestResult.Ingested) {
                        loaded++;
                    }
                } catch (IngestionException e) {
                    err.println("! stored object " + file.sha256().substring(0, 12) + " skipped: " + e.getMessage());
                }
            }
            if (loaded > 0) {
                err.println("Re-indexed " + loaded + " document(s) received earlier from peers.");
            }
            if (blobs > 0) {
                err.println("Holding " + blobs + " binary asset(s) received earlier from peers (see 'assets').");
            }
        }
        if (options.p2p) {
            transfer = trust != null
                    ? new FileTransferService(store, trust, options.transferPort,
                            FileTransferService.DEFAULT_MAX_FILE_BYTES, this::onReceived)
                    : new FileTransferService(store, security, options.transferPort,
                            FileTransferService.DEFAULT_MAX_FILE_BYTES, this::onReceived);
            transfer.start();
            PeerDiscovery.Config config = new PeerDiscovery.Config(nodeId(), options.name, options.group,
                    options.discoveryPort, transfer.port(), Duration.ofSeconds(5), Duration.ofSeconds(20),
                    options.seeds);
            PeerDiscovery.Listener lanListener = new PeerDiscovery.Listener() {
                @Override
                public void peerJoined(PeerInfo peer) {
                    err.println("[lan] " + peer.name() + " joined (" + peer.address().getHostAddress() + ")");
                }

                @Override
                public void peerLeft(PeerInfo peer) {
                    err.println("[lan] " + peer.name() + " left");
                }
            };
            if (trust != null) {
                FileTransferService t = transfer;
                discovery = new PeerDiscovery(config, trust, peer -> t.catalog(peer.transferEndpoint(), peer.nodeId()),
                        store::sharedHashes, lanListener);
            } else {
                loadTrustedPeers();
                transfer.setPushFilter(this::trustedSender);
                discovery = new PeerDiscovery(config, security, store::sharedHashes, lanListener);
            }
            discovery.start();
        }
        err.println("Document workbench node '" + options.name + "' · store " + store.root());
        err.println("  answers : " + (engine == null ? "disabled (GEMINI_API_KEY not set)" : engine.modelId()));
        if (discovery != null) {
            err.println("  LAN     : discovery " + options.group.getHostAddress() + ":" + options.discoveryPort
                    + " on " + (discovery.interfaces().isEmpty() ? "no multicast interface" : discovery.interfaces())
                    + (options.seeds.isEmpty() ? "" : " + seeds " + options.seeds)
                    + " · transfer tcp/" + transfer.port() + " · " + lanMode());
            if (trust != null) {
                err.println("  device  : " + DeviceIdentity.display(trust.identity().fingerprint()) + " · "
                        + trust.trust().devices().size() + " trusted device(s)"
                        + (trust.trust().devices().isEmpty() ? " - isolated until paired ('pair --new' / 'pair')" : ""));
            } else if (!security.enabled()) {
                err.println("  WARNING : LAN open mode - every document indexed on this node can be downloaded, and"
                        + " documents can be pushed to it, by anyone on this network. Set DWB_SECRET on all nodes"
                        + " to restrict sharing to them, or start with --no-p2p.");
            }
        } else {
            err.println("  LAN     : disabled");
        }
    }

    /** {@code zero-trust}, {@code authenticated (DWB_SECRET)} or the open-mode warning. */
    private String lanMode() {
        return trust != null ? "zero-trust (device keys, trusted-peers.tsv)" : security.enabled()
                ? "legacy, authenticated (DWB_SECRET)" : "legacy OPEN mode (set DWB_SECRET, or drop DWB_TRUST=legacy)";
    }

    private String nodeId() throws IOException {
        if (trust != null) {
            return trust.identity().fingerprint();
        }
        Path file = store.root().resolve("node.id");
        if (Files.exists(file)) {
            String id = Files.readString(file, StandardCharsets.US_ASCII).strip();
            if (id.matches("[0-9a-f]{16}")) {
                return id;
            }
        }
        String id = HexFormat.of().formatHex(LanSecurity.nonce(8));
        Files.writeString(file, id, StandardCharsets.US_ASCII);
        return id;
    }

    @Override
    public synchronized void close() {
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
        if (discovery != null) {
            discovery.close();
            discovery = null;
        }
        if (transfer != null) {
            transfer.close();
            transfer = null;
        }
    }

    // ================================================================== command loop

    int repl() {
        boolean interactive = System.console() != null;
        if (interactive) {
            err.println("Type 'help' for commands.");
        }
        while (true) {
            if (interactive) {
                out.print("dwb> ");
                out.flush();
            }
            String line;
            try {
                line = console.readLine();
            } catch (IOException e) {
                return 1;
            }
            if (line == null) {
                return 0;
            }
            if (!execute(line)) {
                return 0;
            }
        }
    }

    int script(List<String> commands) {
        for (String command : commands) {
            err.println("> " + command);
            if (!execute(command)) {
                break;
            }
        }
        return 0;
    }

    /** Runs one command line; returns {@code false} when the session should end. */
    boolean execute(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return true;
        }
        int space = trimmed.indexOf(' ');
        String command = (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT);
        String rest = space < 0 ? "" : trimmed.substring(space + 1).strip();
        try {
            switch (command) {
                case "help", "?" -> out.print(HELP);
                case "quit", "exit" -> {
                    return false;
                }
                case "index" -> index(splitArgs(rest));
                case "find", "search" -> find(rest);
                case "lookup" -> lookup(rest);
                case "docs" -> docs();
                case "assets" -> assets();
                case "open" -> open(rest);
                case "status" -> status();
                case "stats" -> stats();
                case "remove" -> remove(rest);
                case "delete" -> delete(splitArgs(rest));
                case "watch" -> watch(rest);
                case "peers" -> peers(splitArgs(rest));
                case "devices" -> devices(splitArgs(rest));
                case "pair" -> pair(splitArgs(rest));
                case "acl" -> acl(splitArgs(rest));
                case "share" -> share(splitArgs(rest));
                case "send" -> send(splitArgs(rest));
                case "fetch" -> fetch(splitArgs(rest));
                case "ask" -> ask(rest);
                case "model" -> model(rest);
                case "models" -> models();
                case "reset" -> {
                    workbench.context().clear();
                    out.println("Ephemeral context cleared.");
                }
                case "wait" -> {
                    double seconds = Double.parseDouble(rest.isEmpty() ? "1" : rest);
                    if (!(seconds >= 0 && seconds <= MAX_WAIT_SECONDS)) {
                        err.println("wait: seconds must be between 0 and " + MAX_WAIT_SECONDS);
                    } else {
                        Thread.sleep(Duration.ofMillis((long) (seconds * 1000)));
                    }
                }
                default -> err.println("Unknown command '" + command + "'. Type 'help'.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (NumberFormatException e) {
            err.println("! invalid number: " + e.getMessage());
        } catch (IOException e) {
            err.println("! " + e.getMessage());
        } catch (RuntimeException e) {
            err.println("! " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return true;
    }

    // ================================================================== documents

    private void index(List<String> args) {
        if (args.isEmpty()) {
            err.println("usage: index <file|dir>...");
            return;
        }
        int added = 0;
        for (String arg : args) {
            Path root = Path.of(arg);
            List<Path> files = new ArrayList<>();
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root, 16)) {
                    walk.filter(Files::isRegularFile).filter(workbench::supports).sorted().forEach(files::add);
                } catch (IOException | RuntimeException e) {
                    err.println("! cannot walk " + root + ": " + e.getMessage());
                }
            } else {
                files.add(root);
            }
            for (Path file : files) {
                if (indexOne(file, null)) {
                    added++;
                }
            }
        }
        if (added > 0 && discovery != null) {
            discovery.catalogChanged();
        }
    }

    private boolean indexOne(Path file, String displayName) {
        return indexOne(file, displayName, DocumentRecord.LOCAL);
    }

    private boolean indexOne(Path file, String displayName, String origin) {
        if (DocumentParser.classify(file, displayName) == ContentKind.BINARY) {
            return registerBinary(file, displayName, origin) != null;
        }
        try {
            IngestResult result = workbench.ingest(file, displayName, origin);
            switch (result) {
                case IngestResult.Ingested(DocumentRecord d, long millis) -> {
                    store.register(d.sha256(), d.source(), d.fileName());
                    workspace.register(d);
                    autoWatch(d);
                    out.printf("+ %s  %s  %s  %s%d chunk(s)  %,d chars  %d ms%n", d.shortId(), d.type(), d.fileName(),
                            d.pageCount() > 0 ? d.pageCount() + " page(s)  " : "", d.chunks().size(), d.charCount(), millis);
                    if (d.emptyPages() > 0) {
                        err.printf("  note: %d of %d page(s) have no text layer (scanned); they are not searchable%n",
                                d.emptyPages(), d.pageCount());
                    }
                    return true;
                }
                case IngestResult.Duplicate(String sha, Path path) -> {
                    if (!store.has(sha)) {
                        store.register(sha, path, displayName == null ? path.getFileName().toString() : displayName);
                    }
                    out.printf("= %s  already indexed (%s)%n", sha.substring(0, 12), path.getFileName());
                    return false;
                }
                case IngestResult.Registered(BinaryAsset a, long millis) -> {
                    // Only reached for content classify() left undecided above; same track as registerBinary.
                    store.register(a.sha256(), a.source(), a.fileName(), ContentKind.BINARY);
                    workspace.register(a);
                    out.printf("+ %s  BINARY  %s  %s  registered for sharing (not indexed)%n", a.shortId(),
                            a.fileName(), bytes(a.sizeBytes()));
                    return true;
                }
            }
        } catch (IngestionException e) {
            out.printf("! %s  %s: %s%n", file, e.reason(), e.getMessage());
        } catch (IOException e) {
            out.printf("! %s  cannot register for sharing: %s%n", file, e.getMessage());
        }
        return false;
    }

    /**
     * Binary track: one streaming SHA-256 pass, then registration in the content store for raw distribution. No text
     * extraction, no inverted index, no size limit. Returns null (after printing why) when the file is unusable.
     */
    private BinaryAsset registerBinary(Path file, String displayName, String origin) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            out.printf("! %s  NOT_FOUND: %s%n", file, e.getMessage());
            return null;
        }
        TransferMeter meter = new TransferMeter();
        Thread renderer = startRenderer("hash", meter);
        BinaryAsset asset;
        try {
            asset = parser.describeBinary(file, displayName, origin, n -> meter.update(n, size));
            stopRenderer(renderer);
        } catch (IngestionException e) {
            stopRenderer(renderer);
            out.printf("! %s  %s: %s%n", file, e.reason(), e.getMessage());
            return null;
        }
        boolean known = store.has(asset.sha256());
        try {
            store.register(asset.sha256(), asset.source(), asset.fileName(), ContentKind.BINARY);
            workspace.register(asset);
        } catch (IOException e) {
            out.printf("! %s  cannot register for sharing: %s%n", file, e.getMessage());
            return null;
        }
        if (known) {
            out.printf("= %s  already registered (%s)%n", asset.shortId(), asset.fileName());
        } else {
            out.printf("+ %s  BINARY  %s  %s  registered for sharing (not indexed)%n", asset.shortId(),
                    asset.fileName(), bytes(asset.sizeBytes()));
        }
        return asset;
    }

    /** Catalog entry for binary content that sits in the store's object folder (received from a peer). */
    private static BinaryAsset receivedAsset(StoredFile file, String origin) {
        Instant at;
        try {
            at = Files.getLastModifiedTime(file.path()).toInstant();
        } catch (IOException e) {
            at = Instant.now();
        }
        return new BinaryAsset(file.sha256(), file.name(), file.path(), file.size(), at, origin);
    }

    private void onReceived(StoredFile file, InetAddress from) {
        Thread.ofVirtual().name("dwb-ingest-received").start(() -> {
            String peer = peerName(from);
            if (DocumentParser.classify(file.path(), file.name()) == ContentKind.BINARY) {
                // Verified and committed by the transfer service already; a blob is kept as it is, never parsed.
                err.println("[recv] " + file.name() + " (" + file.sha256().substring(0, 12) + ", " + bytes(file.size())
                        + ") from " + peer + " · binary asset stored at " + file.path() + ", not indexed");
                workspace.register(receivedAsset(file, peer));
                if (discovery != null) {
                    discovery.catalogChanged();
                }
                return;
            }
            try {
                IngestResult result = workbench.ingest(file.path(), file.name(), peer);
                String state = result instanceof IngestResult.Ingested(DocumentRecord d, long ms)
                        ? "indexed as '" + d.fileName() + "', " + d.chunks().size() + " chunk(s)"
                        : "already indexed";
                if (result instanceof IngestResult.Ingested(DocumentRecord d, long ms)) {
                    try {
                        store.register(d.sha256(), d.source(), d.fileName());
                    } catch (IOException e) {
                        err.println("[recv] " + d.fileName() + " indexed but not shareable: " + e.getMessage());
                    }
                    workspace.register(d);
                }
                err.println("[recv] " + file.name() + " (" + file.sha256().substring(0, 12) + ") from " + peer
                        + " · " + state);
            } catch (IngestionException e) {
                err.println("[recv] " + file.name() + " stored but not indexed: " + e.getMessage());
            }
            if (discovery != null) {
                discovery.catalogChanged();
            }
        });
    }

    /** Peer name for an address, so incoming documents can be namespaced by their origin. */
    private String peerName(InetAddress address) {
        if (discovery != null) {
            for (PeerInfo peer : discovery.peers()) {
                if (peer.address().equals(address)) {
                    return peer.name();
                }
            }
        }
        return address.getHostAddress();
    }

    private void docs() {
        List<DocumentRecord> docs = workbench.documents();
        if (docs.isEmpty()) {
            out.println("No documents indexed.");
            return;
        }
        for (DocumentRecord d : docs) {
            out.printf("%s  %-4s  %9s  %5s  %4d chunk(s)  %-8s %s%s%n", d.shortId(), d.type(), bytes(d.sizeBytes()),
                    d.pageCount() > 0 ? d.pageCount() + "p" : "-", d.chunks().size(), d.origin(), d.fileName(),
                    watcher != null && watcher.watched().contains(d.source().toAbsolutePath().normalize())
                            ? "  [watched]" : "");
        }
    }

    /** Content this node serves that is not in the text index: binary media, archives and other blobs. */
    private void assets() {
        int count = 0;
        long total = 0;
        for (String sha : store.hashes()) {
            if (workbench.index().contains(sha)) {
                continue;
            }
            Optional<StoredFile> resolved = store.resolve(sha);
            if (resolved.isEmpty()) {
                continue;
            }
            StoredFile f = resolved.get();
            ContentKind kind = store.registeredKind(sha).orElseGet(() -> DocumentParser.classify(f.path(), f.name()));
            if (kind != ContentKind.BINARY) {
                continue;
            }
            count++;
            total += f.size();
            out.printf("%s  %10s  %-7s %s%n", sha.substring(0, 12), bytes(f.size()),
                    store.isShared(sha) ? "shared" : "PRIVATE", f.name());
        }
        out.println(count == 0 ? "No binary assets." : count + " binary asset(s) · " + bytes(total));
    }

    private void stats() {
        SearchIndex.Stats s = workbench.stats();
        out.printf("documents %,d · chunks %,d · terms %,d · postings %,d · tokens %,d · context %d/%d turns%n",
                s.documents(), s.chunks(), s.terms(), s.postings(), s.tokens(), workbench.context().size(),
                EphemeralContext.MAX_TURNS);
    }

    private void remove(String token) {
        Optional<DocumentRecord> doc = workbench.resolve(token);
        if (doc.isEmpty()) {
            Optional<String> blob = binaryPrefix(token);
            if (blob.isPresent() && store.registeredKind(blob.get()).isPresent()) {
                String name = store.resolve(blob.get()).map(StoredFile::name).orElse("?");
                store.unregister(blob.get());
                workspace.remove(blob.get());
                if (discovery != null) {
                    discovery.catalogChanged();
                }
                out.println("- " + blob.get().substring(0, 12) + "  " + name + "  (file left on disk)");
                return;
            }
            err.println("No unique indexed document matches '" + token + "'.");
            return;
        }
        untrack(doc.get());
        if (discovery != null) {
            discovery.catalogChanged();
        }
        out.println("- " + doc.get().shortId() + "  " + doc.get().fileName() + "  (file left on disk)");
    }

    private void open(String token) {
        if (token.isBlank()) {
            err.println("usage: open <hash|name>");
            return;
        }
        try {
            WorkspaceManager.OpenResult result = workspace.open(token);
            out.printf("↗ %s (%s) handed to the OS via %s%n", result.entry().fileName(), result.entry().shortId(),
                    result.method());
        } catch (WorkspaceManager.WorkspaceException e) {
            out.println("! open failed: " + e.getMessage());
        }
    }

    private void status() {
        SearchIndex.Stats index = workbench.stats();
        QuotaGate.Snapshot ai = quota.snapshot();
        out.printf("node      : %s · store %s%n", options.name, store.root());
        out.printf("index     : %,d document(s) · %,d chunk(s) · %,d term(s) · %,d token(s)%n",
                index.documents(), index.chunks(), index.terms(), index.tokens());
        out.printf("workspace : %d file(s) · %s catalogued%n", workspace.size(), bytes(workspace.totalBytes()));
        out.printf("model     : %s · %s%n", selectedProfile.id(),
                engine == null ? "answers disabled (GEMINI_API_KEY not set)" : "answers enabled");
        out.printf("AI quota  : %d/%d used · %d left%s · %s%n", ai.used(), ai.limit(), ai.remaining(),
                ai.resetAt() == null ? "" : " · oldest call expires " + ai.resetAt().truncatedTo(ChronoUnit.SECONDS),
                ai.schedule());
        out.printf("AI usage  : %,d call(s) · %,d prompt token(s) → %,d output token(s)%s%n", ai.calls(),
                ai.promptTokens(), ai.outputTokens(), ai.open() ? "" : " · outside business hours");
        out.printf("watcher   : %s%n", watcher == null || !watcher.running() ? "off"
                : "on · " + watcher.watched().size() + " file(s), debounce " + watcher.debounce().toMillis() + " ms");
        out.printf("context   : %d/%d ephemeral turn(s)%n", workbench.context().size(), EphemeralContext.MAX_TURNS);
        if (discovery == null) {
            out.println("LAN       : disabled");
        } else {
            out.printf("LAN       : %s:%d · transfer tcp/%d · %d peer(s) · %s%n", options.group.getHostAddress(),
                    options.discoveryPort, transfer.port(), discovery.peers().size(), lanMode());
        }
    }

    /** Drops a document from the index, the sharing catalog, the workspace catalog and the watcher. */
    private void untrack(DocumentRecord doc) {
        workbench.remove(doc.sha256());
        store.unregister(doc.sha256());
        workspace.remove(doc.sha256());
        if (watcher != null) {
            watcher.unwatch(doc.source());
        }
        if (discovery != null) {
            discovery.catalogChanged();
        }
    }

    /** {@code delete <hash> --purge-disk [--yes]}: removes the physical file after an explicit confirmation. */
    private void delete(List<String> args) {
        if (args.isEmpty()) {
            err.println("usage: delete <hash|name> --purge-disk [--yes]");
            return;
        }
        boolean purge = args.contains("--purge-disk");
        boolean preapproved = args.contains("--yes");
        String token = args.getFirst();
        Optional<DocumentRecord> doc = workbench.resolve(token)
                .or(() -> workspace.resolve(token).flatMap(e -> workbench.resolve(e.sha256())));
        if (doc.isEmpty()) {
            err.println("No unique indexed document matches '" + token + "'.");
            return;
        }
        DocumentRecord target = doc.get();
        if (!purge) {
            err.println("delete needs --purge-disk (it erases the file); use 'remove " + target.shortId()
                    + "' to untrack it and keep the file.");
            return;
        }
        Optional<WorkspaceManager.Entry> entry = workspace.resolve(target.sha256());
        if (entry.isEmpty()) {
            err.println("Document " + target.shortId() + " is not in the workspace catalog.");
            return;
        }
        Path path = entry.get().path().toAbsolutePath();
        if (!confirm("Delete " + path + " (" + bytes(target.sizeBytes()) + ") from disk? This cannot be undone.",
                preapproved)) {
            out.println("= cancelled; nothing was deleted.");
            return;
        }
        try {
            boolean deleted = workspace.purge(entry.get());
            untrack(target);
            out.printf("%s %s  %s%n", deleted ? "x" : "=", target.shortId(),
                    deleted ? path + " deleted from disk and untracked" : path + " was already gone; untracked");
        } catch (WorkspaceManager.WorkspaceException e) {
            out.println("! delete failed: " + e.getMessage());
        }
    }

    /** Interactive y/N prompt; in non-interactive runs only {@code --yes} may approve. */
    private boolean confirm(String question, boolean preapproved) {
        if (preapproved) {
            err.println(question + " [--yes]");
            return true;
        }
        if (System.console() == null) {
            err.println("Refusing to delete without a terminal; re-run interactively or pass --yes.");
            return false;
        }
        out.print(question + " [y/N] ");
        out.flush();
        try {
            String answer = console.readLine();
            return answer != null && answer.strip().equalsIgnoreCase("y");
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code watch} / {@code watch on} / {@code watch off}: background auto-reindexing. */
    private void watch(String rest) {
        String argument = rest.strip().toLowerCase(Locale.ROOT);
        switch (argument) {
            case "", "status" -> {
                if (watcher == null || !watcher.running()) {
                    out.println("watch: off");
                } else {
                    out.printf("watch: on · %d file(s) in %d director(y/ies) · debounce %d ms%n",
                            watcher.watched().size(), watcher.directoryCount(), watcher.debounce().toMillis());
                }
            }
            case "on" -> startWatching();
            case "off" -> {
                if (watcher == null) {
                    out.println("watch: already off");
                } else {
                    watcher.close();
                    watcher = null;
                    out.println("watch: off");
                }
            }
            default -> err.println("usage: watch [on|off|status]");
        }
    }

    private void startWatching() {
        if (watcher != null && watcher.running()) {
            out.println("watch: already on");
            return;
        }
        FileWatcherService service = new FileWatcherService(new FileWatcherService.Listener() {
            @Override
            public void modified(Path file) {
                reindex(file);
            }

            @Override
            public void deleted(Path file) {
                workbench.documents().stream()
                        .filter(d -> d.source().equals(file))
                        .findFirst()
                        .ifPresent(doc -> {
                            untrack(doc);
                            err.println("[watch] " + doc.fileName() + " disappeared from disk; untracked");
                        });
            }

            @Override
            public void failed(Path path, Exception error) {
                err.println("[watch] " + path + ": " + error.getMessage());
            }
        });
        try {
            service.start();
        } catch (IOException e) {
            err.println("! watch failed to start: " + e.getMessage());
            return;
        }
        watcher = service;
        int registered = 0;
        for (DocumentRecord doc : workbench.documents()) {
            registered += autoWatch(doc) ? 1 : 0;
        }
        out.printf("watch: on · %d file(s) registered · debounce %d ms%n", registered, service.debounce().toMillis());
    }

    private boolean autoWatch(DocumentRecord doc) {
        if (watcher == null || !watcher.running() || !doc.local()) {
            return false;
        }
        try {
            return watcher.watch(doc.source());
        } catch (IOException | RuntimeException e) {
            err.println("[watch] cannot watch " + doc.source() + ": " + e.getMessage());
            return false;
        }
    }

    /** Re-ingests a changed file: same bytes are ignored, new bytes replace the old chunks and re-announce. */
    private void reindex(Path file) {
        Optional<DocumentRecord> current = workbench.documents().stream()
                .filter(d -> d.source().equals(file))
                .findFirst();
        try {
            String hash = Hashing.sha256Hex(file);
            if (current.isPresent() && current.get().sha256().equals(hash)) {
                return;
            }
            // The old version stays indexed and shared until the new one has been extracted: a file locked or
            // half-written by the application saving it never makes the document disappear.
            IngestResult result = current.isPresent()
                    ? workbench.replace(current.get().sha256(), file, null, DocumentRecord.LOCAL)
                    : workbench.ingest(file, null, DocumentRecord.LOCAL);
            if (current.isPresent() && !workbench.index().contains(current.get().sha256())) {
                store.unregister(current.get().sha256());
                workspace.remove(current.get().sha256());
            }
            if (result instanceof IngestResult.Ingested(DocumentRecord d, long millis)) {
                store.register(d.sha256(), d.source(), d.fileName());
                workspace.register(d);
                autoWatch(d);
                err.printf("[watch] %s re-indexed: %s → %s · %d chunk(s) · %d ms%n", d.fileName(),
                        current.map(DocumentRecord::shortId).orElse("new"), d.shortId(), d.chunks().size(), millis);
            }
            if (discovery != null) {
                discovery.catalogChanged();
            }
        } catch (IngestionException e) {
            err.println("[watch] " + file.getFileName() + " could not be re-indexed: " + e.getMessage()
                    + (current.isPresent() ? " (previous version kept)" : ""));
        } catch (IOException e) {
            err.println("[watch] " + file.getFileName() + " could not be hashed: " + e.getMessage());
        }
    }

    // ================================================================== search

    private void find(String rest) {
        int k = 10;
        String query = rest;
        int flag = rest.lastIndexOf("-k ");
        if (flag >= 0 && (flag == 0 || rest.charAt(flag - 1) == ' ')) {
            k = Integer.parseInt(rest.substring(flag + 3).strip());
            query = rest.substring(0, flag).strip();
            if (k < 1 || k > 200) {
                err.println("find: -k must be between 1 and 200");
                return;
            }
        }
        if (query.isEmpty()) {
            err.println("usage: find <query> [-k N]");
            return;
        }
        SearchResult result = workbench.find(query, k);
        out.printf("%d hit(s) · %.3f ms · %d matching chunk(s) · terms: %s%n", result.hits().size(),
                result.elapsedMillis(), result.matchedChunks(), String.join(", ", result.terms()));
        int rank = 1;
        for (SearchHit hit : result.hits()) {
            out.printf("[%d] %s%s · chunk %d · @%d · score %.2f · %s%n", rank++, hit.fileName(),
                    hit.page() > 0 ? " · p." + hit.page() : "", hit.chunkIndex(), hit.firstMatchOffset(), hit.score(),
                    hit.docId().substring(0, 12));
            out.println("    " + highlight(hit));
        }
    }

    private String highlight(SearchHit hit) {
        String open = ansi ? "\u001b[1;33m" : "«";
        String close = ansi ? "\u001b[0m" : "»";
        String s = hit.snippet();
        StringBuilder sb = new StringBuilder(s.length() + 32);
        if (hit.clippedStart()) {
            sb.append('…');
        }
        int cursor = 0;
        for (Location m : hit.matches()) {
            int from = (int) (m.offset() - hit.snippetOffset());
            int to = from + m.length();
            if (from < cursor || from < 0 || to > s.length()) {
                continue;
            }
            sb.append(s, cursor, from).append(open).append(s, from, to).append(close);
            cursor = to;
        }
        sb.append(s, cursor, s.length());
        if (hit.clippedEnd()) {
            sb.append('…');
        }
        return sb.toString();
    }

    private void lookup(String term) {
        if (term.isBlank()) {
            err.println("usage: lookup <term>");
            return;
        }
        long started = System.nanoTime();
        List<Location> locations = workbench.index().lookup(term);
        double ms = (System.nanoTime() - started) / 1e6;
        out.printf("%,d location(s) for '%s' · %.3f ms%n", locations.size(), term, ms);
        for (Location l : locations.subList(0, Math.min(20, locations.size()))) {
            String name = workbench.index().document(l.docId()).map(DocumentRecord::fileName).orElse("?");
            out.printf("  %s  %s  chunk %d  pos %d  @%d+%d%n", l.docId().substring(0, 12), name, l.chunkIndex(),
                    l.position(), l.offset(), l.length());
        }
        if (locations.size() > 20) {
            out.println("  …");
        }
    }

    // ================================================================== LAN

    private boolean requireLan() {
        if (discovery == null || transfer == null) {
            err.println("LAN features are disabled (--no-p2p).");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ sharing and trust

    /** Node ids whose pushes this node accepts; empty = everyone (the original behaviour). */
    private final Set<String> trustedPeers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Path trustedFile() {
        return store.root().resolve("trusted-peers.txt");
    }

    private void loadTrustedPeers() throws IOException {
        trustedPeers.clear();
        if (Files.isRegularFile(trustedFile())) {
            for (String line : Files.readAllLines(trustedFile(), StandardCharsets.UTF_8)) {
                String id = line.strip();
                if (id.matches("[0-9a-f]{16}")) {
                    trustedPeers.add(id);
                }
            }
        }
    }

    private void saveTrustedPeers() throws IOException {
        Files.write(trustedFile(), new java.util.TreeSet<>(trustedPeers), StandardCharsets.UTF_8);
    }

    /** A push is accepted from anyone until a trust list exists, then only from listed nodes' addresses. */
    private boolean trustedSender(InetAddress from) {
        if (trustedPeers.isEmpty()) {
            return true;
        }
        PeerDiscovery d = discovery;
        return d != null && d.peers().stream()
                .anyMatch(p -> p.address().equals(from) && trustedPeers.contains(p.nodeId()));
    }

    /** {@code share [--list] | share on|off <hash|name>}: which indexed documents peers can see and download. */
    private void share(List<String> args) throws IOException {
        if (args.isEmpty() || args.getFirst().equals("--list")) {
            List<DocumentRecord> docs = workbench.documents();
            int off = 0;
            for (DocumentRecord d : docs) {
                boolean shared = store.isShared(d.sha256());
                off += shared ? 0 : 1;
                out.printf("%s  %-6s %s%n", d.shortId(), shared ? "shared" : "PRIVATE", d.fileName());
            }
            out.printf("%d document(s), %d kept private%s%n", docs.size(), off,
                    discovery == null ? " (LAN is disabled)" : trust != null || security.enabled() ? ""
                            : " · open mode: shared documents are visible to everyone on this network");
            return;
        }
        if (args.size() != 2 || !(args.get(0).equals("on") || args.get(0).equals("off"))) {
            err.println("usage: share [--list] | share on <hash|name> | share off <hash|name>");
            return;
        }
        String token = args.get(1);
        Optional<DocumentRecord> doc = workbench.resolve(token)
                .or(() -> workspace.resolve(token).flatMap(e -> workbench.resolve(e.sha256())));
        Optional<String> sha = doc.map(DocumentRecord::sha256).or(() -> binaryPrefix(token));
        if (sha.isEmpty()) {
            err.println("No unique indexed document matches '" + token + "'.");
            return;
        }
        String name = doc.map(DocumentRecord::fileName)
                .orElseGet(() -> store.resolve(sha.get()).map(StoredFile::name).orElse(sha.get().substring(0, 12)));
        boolean on = args.get(0).equals("on");
        boolean changed = store.setShared(sha.get(), on);
        if (discovery != null) {
            discovery.catalogChanged();
        }
        out.println((on ? "shared: " : "private: ") + name + (changed ? "" : " (unchanged)"));
    }

    /** Hash prefix of held content that is not an indexed document (binary assets). */
    private Optional<String> binaryPrefix(String token) {
        return store.resolvePrefix(token).filter(sha -> !workbench.index().contains(sha));
    }

    private void peers(List<String> args) throws IOException {
        if (!requireLan()) {
            return;
        }
        if (!args.isEmpty() && trust != null) {
            err.println("zero-trust mode: devices are trusted by pairing - see 'devices' and 'pair'");
            return;
        }
        if (!args.isEmpty()) {
            switch (args.getFirst()) {
                case "--trusted" -> {
                    if (trustedPeers.isEmpty()) {
                        out.println("no trust list: pushes are accepted from every peer");
                    }
                    for (String id : new java.util.TreeSet<>(trustedPeers)) {
                        out.println(id + "  " + discovery.find(id).map(PeerInfo::name).orElse("(not seen right now)"));
                    }
                    return;
                }
                case "--trust" -> {
                    if (args.size() != 2) {
                        err.println("usage: peers --trust <name|id>");
                        return;
                    }
                    Optional<PeerInfo> peer = discovery.find(args.get(1));
                    if (peer.isEmpty()) {
                        err.println("No single discovered peer matches '" + args.get(1) + "' (see 'peers').");
                        return;
                    }
                    trustedPeers.add(peer.get().nodeId());
                    saveTrustedPeers();
                    out.println("trusted: " + peer.get().name() + " (" + peer.get().nodeId() + "); pushes from other"
                            + " peers are now refused" + (security.enabled() ? ""
                            : ". Note: without DWB_SECRET a peer's identity is not verified"));
                    return;
                }
                case "--untrust" -> {
                    if (args.size() != 2) {
                        err.println("usage: peers --untrust <id>");
                        return;
                    }
                    String id = discovery.find(args.get(1)).map(PeerInfo::nodeId).orElse(args.get(1));
                    out.println(trustedPeers.remove(id) ? "no longer trusted: " + id : "not in the trust list: " + id);
                    saveTrustedPeers();
                    if (trustedPeers.isEmpty()) {
                        out.println("trust list empty: pushes are accepted from every peer again");
                    }
                    return;
                }
                default -> {
                    err.println("usage: peers [--trusted | --trust <name|id> | --untrust <id>]");
                    return;
                }
            }
        }
        List<PeerInfo> peers = discovery.peers();
        if (peers.isEmpty()) {
            out.println("No peers discovered yet (announce interval 5 s).");
            return;
        }
        Set<String> mine = store.hashes();
        for (PeerInfo p : peers) {
            long missing = p.hashes().stream().filter(h -> !mine.contains(h)).count();
            long age = Duration.between(p.lastSeen(), Instant.now()).toSeconds();
            out.printf("%-20s %s  %s:%d  docs %d%s  new-for-us %d  seen %ds ago%n", p.name(),
                    trust == null ? p.nodeId() : DeviceIdentity.display(p.nodeId()) + " " + trust.trust()
                            .find(p.nodeId()).map(TrustStore.Device::labels).orElse(""),
                    p.address().getHostAddress(), p.transferPort(), p.advertised(),
                    p.catalogComplete() ? "" : " (catalog " + p.hashes().size() + "/" + p.advertised() + ")",
                    missing, age);
            p.hashes().stream().filter(h -> !mine.contains(h)).limit(10)
                    .forEach(h -> out.println("    available: " + h.substring(0, 12)));
        }
    }

    // ------------------------------------------------------------------ zero trust: devices, pairing, access lists

    private boolean requireZeroTrust() {
        if (!requireLan()) {
            return false;
        }
        if (trust == null) {
            err.println("This node runs the legacy LAN mode (DWB_TRUST=legacy); devices, pairing and access lists"
                    + " exist only in zero-trust mode.");
            return false;
        }
        return true;
    }

    private void devices(List<String> args) throws IOException {
        if (!requireZeroTrust()) {
            return;
        }
        TrustStore list = trust.trust();
        String sub = args.isEmpty() ? "" : args.getFirst();
        switch (sub) {
            case "" -> {
                out.println("this device: " + DeviceIdentity.display(trust.identity().fingerprint()) + "  ("
                        + trust.identity().fingerprint() + ")");
                List<TrustStore.Device> all = list.devices();
                if (all.isEmpty()) {
                    out.println("no trusted devices: this node is isolated (pair --new on a trusted device, pair here)");
                }
                for (TrustStore.Device d : all) {
                    boolean online = discovery.find(d.fingerprint()).isPresent();
                    out.printf("%-20s %s  %-40s %s%n", d.name(), DeviceIdentity.display(d.fingerprint()), d.labels(),
                            online ? "online" : "not seen right now");
                }
                trust.pendingPairing().ifPresent(t -> out.println("pairing window open until " + t.expires()
                        + " for role=" + t.role()));
            }
            case "--pending" -> {
                List<ZeroTrust.Sighting> seen = trust.unpaired();
                out.println(seen.isEmpty() ? "no unpaired device announced itself in the last 10 minutes"
                        : "unpaired devices (isolated: no catalog, no transfers, until paired):");
                for (ZeroTrust.Sighting p : seen) {
                    out.printf("  %-20s %s  %s%n", p.name(), DeviceIdentity.display(p.fingerprint()),
                            p.address().getHostAddress());
                }
            }
            case "--remove" -> {
                TrustStore.Device d = trustedDevice(args, 2, "devices --remove <device>");
                if (d != null) {
                    trust.forget(d.fingerprint(), store);
                    discovery.catalogChanged();
                    out.println("no longer trusted: " + d.name() + " (" + DeviceIdentity.display(d.fingerprint()) + ")");
                }
            }
            case "--role" -> {
                TrustStore.Device d = trustedDevice(args, 3, "devices --role <device> FULL_PEER|RESTRICTED_GUEST");
                if (d == null) {
                    return;
                }
                Optional<DeviceRole> role = DeviceRole.parse(args.get(2));
                if (role.isEmpty()) {
                    err.println("role must be FULL_PEER or RESTRICTED_GUEST");
                    return;
                }
                TrustStore.Device changed = list.setRole(d.fingerprint(), role.get());
                discovery.catalogChanged();
                out.println(changed.name() + ": " + changed.labels());
            }
            case "--dept" -> {
                TrustStore.Device d = trustedDevice(args, 3, "devices --dept <device> <NAME|->");
                if (d != null) {
                    TrustStore.Device changed = list.setDepartment(d.fingerprint(), args.get(2));
                    discovery.catalogChanged();
                    out.println(changed.name() + ": " + changed.labels());
                }
            }
            default -> err.println("usage: devices [--pending | --remove <device> | --role <device> <ROLE>"
                    + " | --dept <device> <NAME|->]");
        }
    }

    private TrustStore.Device trustedDevice(List<String> args, int size, String usage) {
        if (args.size() != size) {
            err.println("usage: " + usage);
            return null;
        }
        Optional<TrustStore.Device> d = trust.trust().resolve(args.get(1));
        if (d.isEmpty()) {
            err.println("No single trusted device matches '" + args.get(1) + "' (see 'devices').");
            return null;
        }
        return d.get();
    }

    private void pair(List<String> args) {
        if (!requireZeroTrust()) {
            return;
        }
        if (!args.isEmpty() && args.getFirst().equals("--new")) {
            DeviceRole role = DeviceRole.FULL_PEER;
            String department = "";
            for (int i = 1; i < args.size(); i++) {
                switch (args.get(i)) {
                    case "--guest" -> role = DeviceRole.RESTRICTED_GUEST;
                    case "--dept" -> department = i + 1 < args.size() ? args.get(++i) : "";
                    default -> {
                        err.println("usage: pair --new [--guest] [--dept NAME]");
                        return;
                    }
                }
            }
            ZeroTrust.PairingTicket ticket = trust.openPairing(role, department);
            out.println("pairing PIN: " + ticket.pin() + "   valid for 5 minutes, one attempt · the new device joins as "
                    + "role=" + role + (ticket.department().isEmpty() ? "" : " department=" + ticket.department()));
            out.println("on the new device run:  pair <this-address>:" + transfer.port() + " " + ticket.pin());
            out.println("then compare the verification code both devices print.");
            return;
        }
        if (args.size() == 1 && args.getFirst().equals("--cancel")) {
            trust.cancelPairing();
            out.println("pairing window closed");
            return;
        }
        if (args.size() != 2) {
            err.println("usage: pair --new [--guest] [--dept NAME] | pair <peer|host:port> <PIN> | pair --cancel");
            return;
        }
        InetSocketAddress target = resolvePeer(args.get(0));
        if (target == null) {
            return;
        }
        try {
            FileTransferService.PairingResult r = transfer.pair(target, args.get(1), options.name);
            discovery.catalogChanged();
            out.println("paired with " + r.device().name() + " (" + DeviceIdentity.display(r.device().fingerprint())
                    + "), trusted as " + r.device().labels());
            out.println("verification code: " + r.verificationCode() + "  - the other device must show the same code;"
                    + " if not, run: devices --remove " + r.device().fingerprint().substring(0, 12));
        } catch (IOException | IllegalArgumentException e) {
            out.println("! pairing failed: " + e.getMessage());
        }
    }

    private void acl(List<String> args) throws IOException {
        if (!requireZeroTrust()) {
            return;
        }
        if (args.isEmpty()) {
            java.util.Map<String, List<String>> all = store.acl().snapshot();
            if (all.isEmpty()) {
                out.println("no access lists: every shared document is visible to every FULL_PEER, to no guest");
            }
            all.forEach((sha, entries) -> out.println(sha.substring(0, 12) + "  " + describeAcl(entries)));
            return;
        }
        String token = args.getFirst();
        Optional<String> sha = workbench.resolve(token).map(DocumentRecord::sha256)
                .or(() -> workspace.resolve(token).map(e -> e.sha256()))
                .or(() -> store.resolvePrefix(token));
        if (sha.isEmpty()) {
            err.println("No unique local document matches '" + token + "'.");
            return;
        }
        if (args.size() == 3 && (args.get(1).equals("--grant") || args.get(1).equals("--revoke"))) {
            String entry;
            String target = args.get(2);
            if (target.regionMatches(true, 0, "dept:", 0, 5) || target.regionMatches(true, 0, "department=", 0, 11)) {
                entry = AccessList.entry(target);
            } else {
                Optional<TrustStore.Device> d = trust.trust().resolve(target);
                if (d.isEmpty()) {
                    err.println("No single trusted device matches '" + target + "' (see 'devices').");
                    return;
                }
                entry = args.get(1).equals("--grant") ? ZeroTrust.grantEntry(d.get()) : d.get().fingerprint();
            }
            boolean changed = args.get(1).equals("--grant") ? store.acl().grant(sha.get(), entry)
                    : store.acl().revoke(sha.get(), entry);
            discovery.catalogChanged();
            if (!changed) {
                out.println("(unchanged)");
            }
        } else if (args.size() != 1) {
            err.println("usage: acl [<hash|name> [--grant <device|dept:NAME> | --revoke <device|dept:NAME>]]");
            return;
        }
        List<String> entries = store.acl().entries(sha.get());
        out.println(sha.get().substring(0, 12) + "  " + (entries.isEmpty()
                ? "no access list - visible to every FULL_PEER, to no guest" : describeAcl(entries)));
    }

    private String describeAcl(List<String> entries) {
        List<String> names = new ArrayList<>();
        for (String e : entries) {
            boolean once = e.startsWith("once:");
            String fp = once ? e.substring(5) : e;
            names.add(e.startsWith("dept:") ? e : trust.trust().find(fp).map(TrustStore.Device::name)
                    .orElse(DeviceIdentity.display(fp)) + (once ? " (one-shot)" : ""));
        }
        return String.join(", ", names);
    }

    private void send(List<String> args) {
        if (!requireLan()) {
            return;
        }
        if (args.size() != 2) {
            err.println("usage: send <hash|path> <peer>");
            return;
        }
        String sha = resolveLocalContent(args.get(0));
        if (sha == null) {
            return;
        }
        InetSocketAddress target = resolvePeer(args.get(1));
        if (target == null) {
            return;
        }
        String expected = discovery.find(args.get(1)).map(PeerInfo::nodeId).orElse(null);
        TransferMeter meter = new TransferMeter();
        Thread renderer = startRenderer("send", meter);
        try {
            TransferResult r = transfer.push(target, expected, sha, meter);
            stopRenderer(renderer);
            switch (r.outcome()) {
                case ALREADY_PRESENT -> out.printf("= peer already has %s%n", sha.substring(0, 12));
                default -> out.printf("→ sent %s (%s%s) in %d ms%s, verified by peer%n", sha.substring(0, 12),
                        bytes(r.bytes()), r.resumedFrom() > 0 ? ", resumed at " + bytes(r.resumedFrom()) : "", r.millis(),
                        rate(r.bytes(), r.millis()));
            }
        } catch (IOException e) {
            stopRenderer(renderer);
            out.println("! send failed: " + e.getMessage());
        }
    }

    private void fetch(List<String> args) {
        if (!requireLan()) {
            return;
        }
        if (args.isEmpty() || args.size() > 2) {
            err.println("usage: fetch <hash> [peer]");
            return;
        }
        String prefix = args.get(0).toLowerCase(Locale.ROOT);
        List<String> candidates = Hashing.isSha256Hex(prefix) && args.size() == 2
                ? List.of(prefix)
                : discovery.peers().stream().flatMap(p -> p.hashes().stream())
                        .filter(h -> h.startsWith(prefix)).distinct().toList();
        if (candidates.size() != 1 || prefix.length() < 4) {
            err.println(candidates.isEmpty() ? "No peer advertises '" + prefix + "'."
                    : "Hash prefix '" + prefix + "' is ambiguous or too short.");
            return;
        }
        String sha = candidates.getFirst();
        InetSocketAddress source;
        String origin;
        String expected;
        if (args.size() == 2) {
            source = resolvePeer(args.get(1));
            origin = discovery.find(args.get(1)).map(PeerInfo::name).orElse(args.get(1));
            expected = discovery.find(args.get(1)).map(PeerInfo::nodeId).orElse(null);
        } else {
            PeerInfo holder = discovery.holders(sha).stream().findFirst().orElse(null);
            source = holder == null ? null : holder.transferEndpoint();
            origin = holder == null ? null : holder.name();
            expected = holder == null ? null : holder.nodeId();
        }
        if (source == null) {
            return;
        }
        TransferMeter meter = new TransferMeter();
        Thread renderer = startRenderer("fetch", meter);
        try {
            TransferResult r = transfer.pull(source, expected, sha, meter);
            stopRenderer(renderer);
            if (r.outcome() == FileTransferService.Outcome.ALREADY_PRESENT) {
                out.printf("= %s already present locally%n", sha.substring(0, 12));
            } else {
                out.printf("← received %s '%s' (%s%s) in %d ms%s, SHA-256 verified%n", sha.substring(0, 12),
                        r.file().name(), bytes(r.bytes()),
                        r.resumedFrom() > 0 ? ", resumed at " + bytes(r.resumedFrom()) : "", r.millis(),
                        rate(r.bytes(), r.millis()));
                if (DocumentParser.classify(r.file().path(), r.file().name()) == ContentKind.BINARY) {
                    // Hashed and verified while streaming: a blob is kept as it is, never parsed or re-hashed.
                    out.println("  binary asset stored at " + r.file().path() + " (not indexed)");
                    workspace.register(receivedAsset(r.file(), origin));
                    discovery.catalogChanged();
                } else if (indexOne(r.file().path(), r.file().name(), origin)) {
                    discovery.catalogChanged();
                }
            }
        } catch (IOException e) {
            stopRenderer(renderer);
            out.println("! fetch failed: " + e.getMessage());
        }
    }

    /** Hash prefix of local content, or a path that is indexed on the fly. */
    private String resolveLocalContent(String token) {
        Path path = Path.of(token);
        if (!Hashing.isSha256Hex(token) && Files.isRegularFile(path)) {
            if (DocumentParser.classify(path, null) == ContentKind.BINARY) {
                // Hash once while registering; hashing an 11 GB video a second time would double the wait.
                BinaryAsset asset = registerBinary(path, null, DocumentRecord.LOCAL);
                if (asset != null && discovery != null) {
                    discovery.catalogChanged();
                }
                return asset == null ? null : asset.sha256();
            }
            indexOne(path, null);
            try {
                String sha = Hashing.sha256Hex(path);
                if (discovery != null) {
                    discovery.catalogChanged();
                }
                return store.has(sha) ? sha : null;
            } catch (IOException e) {
                err.println("! cannot hash " + path + ": " + e.getMessage());
                return null;
            }
        }
        Optional<String> sha = store.resolvePrefix(token);
        if (sha.isEmpty()) {
            err.println("No unique local document matches '" + token + "'.");
            return null;
        }
        return sha.get();
    }

    private InetSocketAddress resolvePeer(String token) {
        Optional<PeerInfo> peer = discovery.find(token);
        if (peer.isPresent()) {
            return peer.get().transferEndpoint();
        }
        int colon = token.lastIndexOf(':');
        if (colon > 0) {
            try {
                String host = token.substring(0, colon).replace("[", "").replace("]", "");
                return new InetSocketAddress(host, Integer.parseInt(token.substring(colon + 1)));
            } catch (IllegalArgumentException e) {
                // fall through to the error below
            }
        }
        err.println("Unknown peer '" + token + "' (use a name, node id prefix or host:port).");
        return null;
    }

    /** Below this size a non-terminal run prints no progress lines (small documents finish almost instantly). */
    private static final long QUIET_PROGRESS_BYTES = 256L << 20;
    private static final long PROGRESS_INTERVAL_MILLIS = 250;

    /**
     * Starts a virtual thread that samples {@code meter} and renders progress, so console output never runs on (or
     * slows down) the transfer thread. On a terminal: one self-overwriting line with percentage, bytes, MB/s and ETA.
     * Otherwise, for large files only: one line per 10 %.
     */
    private Thread startRenderer(String label, TransferMeter meter) {
        return Thread.ofVirtual().name("dwb-progress").start(() -> {
            int lastDecile = 0;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(PROGRESS_INTERVAL_MILLIS);
                    TransferMeter.Snapshot s = meter.snapshot();
                    if (!s.started()) {
                        continue;
                    }
                    if (ansi) {
                        err.print("\r\u001b[K" + progressLine(label, s));
                        err.flush();
                    } else if (s.total() >= QUIET_PROGRESS_BYTES) {
                        int decile = (int) (s.percent() / 10);
                        if (decile > lastDecile && decile < 10) {
                            lastDecile = decile;
                            err.println(progressLine(label, s));
                        }
                    }
                }
            } catch (InterruptedException e) {
                // stopped by stopRenderer
            }
        });
    }

    /** Stops a renderer and waits for it, so no progress output can interleave with the result line. */
    private void stopRenderer(Thread renderer) {
        renderer.interrupt();
        try {
            renderer.join(Duration.ofSeconds(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (ansi) {
            err.print("\r\u001b[K");
            err.flush();
        }
    }

    private static String progressLine(String label, TransferMeter.Snapshot s) {
        String eta = s.etaSeconds() < 0 ? "--:--"
                : String.format(Locale.ROOT, "%d:%02d", s.etaSeconds() / 60, s.etaSeconds() % 60);
        return String.format(Locale.ROOT, "  %-5s %5.1f%%  %s / %s  %.1f MB/s  ETA %s", label, s.percent(),
                bytes(s.transferred()), bytes(s.total()), s.megabytesPerSecond(), eta);
    }

    /** Average-rate suffix for a finished transfer; empty when there is nothing meaningful to report. */
    private static String rate(long bytes, long millis) {
        return bytes <= 0 || millis <= 0 ? ""
                : String.format(Locale.ROOT, " · %.1f MB/s", bytes / (1024.0 * 1024.0) / (millis / 1000.0));
    }

    // ================================================================== answering

    private void ask(String rest) {
        String question = rest;
        if (rest.startsWith("--model ")) {
            String[] parts = rest.substring(8).strip().split("\\s+", 2);
            Optional<ModelProfile> profile = ModelProfile.parse(parts[0]);
            if (profile.isEmpty()) {
                err.println("Unknown model '" + parts[0] + "'. Available: " + ModelProfile.ids());
                return;
            }
            selectModel(profile.get());
            question = parts.length > 1 ? parts[1] : "";
        }
        if (question.isBlank()) {
            err.println("usage: ask [--model ID] <question>");
            return;
        }
        try {
            boolean[] started = {false};
            Workbench.AskOutcome outcome = workbench.ask(question, delta -> {
                started[0] = true;
                out.print(delta);
                out.flush();
            });
            if (!outcome.answered()) {
                out.printf(outcome.outcome() == Workbench.Outcome.NO_MATCH
                                ? "No indexed passage matches this question (%.2f ms); the model was not called.%n"
                                : "Best match scores %2$.2f, below the %3$.2f evidence threshold (%1$.2f ms); "
                                + "the model was not called.%n",
                        outcome.retrievalMillis(), outcome.topScore(), Workbench.DEFAULT_MIN_SCORE);
                return;
            }
            if (started[0]) {
                out.println();
            }
            StringBuilder sources = new StringBuilder("Sources:");
            for (Workbench.Source s : outcome.sources()) {
                sources.append(' ').append('[').append(s.tag()).append("] ").append(s.fileName())
                        .append(s.page() > 0 ? " p." + s.page() : "").append(" #").append(s.chunkIndex());
            }
            out.println(sources);
            var st = outcome.stats();
            out.printf("— %s · retrieval %.2f ms · first token %d ms · total %.1f s · tokens %d→%d · context %d/%d%n",
                    st.model(), outcome.retrievalMillis(), st.firstTokenMillis(), st.totalMillis() / 1000.0,
                    st.promptTokens(), st.outputTokens(), workbench.context().size(), EphemeralContext.MAX_TURNS);
            if (!"STOP".equals(st.finishReason())) {
                out.println("  (finish reason: " + st.finishReason() + ")");
            }
        } catch (AnswerException e) {
            out.println();
            out.println("! " + e.kind() + ": " + e.getMessage());
        }
    }

    private void model(String id) {
        if (id.isBlank()) {
            out.println("model: " + selectedProfile.id() + (engine == null ? " (answers disabled: GEMINI_API_KEY not set)" : ""));
            return;
        }
        Optional<ModelProfile> profile = ModelProfile.parse(id);
        if (profile.isEmpty()) {
            err.println("Unknown model '" + id + "'. Available: " + ModelProfile.ids());
            return;
        }
        selectModel(profile.get());
        out.println("model: " + selectedProfile.id());
    }

    private void selectModel(ModelProfile profile) {
        selectedProfile = profile;
        if (engine != null) {
            engine.select(profile);
        }
    }

    private void models() {
        for (ModelProfile p : ModelProfile.values()) {
            out.printf("%s %-24s max output %,d tokens · source budget %,d chars%n",
                    p == selectedProfile ? "*" : " ", p.id(), p.maxOutputTokens(), p.contextCharBudget());
        }
    }

    // ================================================================== helpers

    private static String bytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        if (n < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        }
        if (n < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", n / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", n / (1024.0 * 1024 * 1024));
    }

    /** Splits on whitespace, honouring double quotes (quotes are removed). */
    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    out.add(current.toString());
                    current.setLength(0);
                    any = false;
                }
            } else {
                current.append(c);
                any = true;
            }
        }
        if (any) {
            out.add(current.toString());
        }
        return out;
    }

    private static void configureLogging(boolean verbose) {
        Logger root = Logger.getLogger("");
        Level level = verbose ? Level.FINE : Level.WARNING;
        root.setLevel(level);
        for (var handler : root.getHandlers()) {
            handler.setLevel(level);
        }
        Logger.getLogger("org.apache.pdfbox").setLevel(verbose ? Level.WARNING : Level.SEVERE);
        Logger.getLogger("org.apache.fontbox").setLevel(verbose ? Level.WARNING : Level.SEVERE);
    }

    private static String usage() {
        return """
                Document workbench node (headless)

                Usage: java -cp <app>:<pdfbox-app-3.x.jar> org.example.App [options] [-c "command"]...

                Options
                  --store DIR            content store and node identity (default $DWB_HOME or ~/.docbench)
                  --name NAME            node name shown to peers (default host name)
                  --model ID             %s (default %s, env DWB_MODEL)
                  --discovery-port N     UDP multicast port (default %d)
                  --group ADDR           multicast group (default %s)
                  --transfer-port N      TCP transfer port, 0 = any free port (default %d)
                  --seed HOST:PORT       unicast discovery peer, repeatable (for networks without multicast)
                  --no-p2p               disable discovery and transfer
                  --no-autoload          do not re-index documents received earlier
                  --api-base URL         Generative Language base URL (default %s)
                  --verbose              diagnostic logging to stderr
                  --hash-passphrase      print an admin.passphrase.hash line for the central IT policy, then exit
                  -c "command"           run a command and exit; repeatable (otherwise read commands from stdin)

                Environment
                  GEMINI_API_KEY   enables answers          DWB_TRUST    zero (default) | legacy LAN mode
                  DWB_SECRET       legacy mode only: LAN pre-shared key (HMAC)
                  DWB_HOME         store directory          DWB_MODEL    startup model id
                  DWB_AI_QUOTA     AI calls per 24 h (default %d)        DWB_AI_RATE   AI calls per minute (default %d)
                  DWB_AI_HOURS     business window, e.g. 09:00-18:00     DWB_AI_DAYS   e.g. MON-FRI

                """.formatted(ModelProfile.ids(), ModelProfile.DEFAULT.id(), PeerDiscovery.DEFAULT_PORT,
                PeerDiscovery.DEFAULT_GROUP, FileTransferService.DEFAULT_PORT, GeminiStreamEngine.DEFAULT_BASE_URI,
                QuotaManager.DEFAULT_DAILY_QUOTA, QuotaManager.DEFAULT_PER_MINUTE)
                + HELP;
    }

    // ================================================================== options

    static final class Options {
        Path store;
        String name;
        ModelProfile model;
        int discoveryPort = PeerDiscovery.DEFAULT_PORT;
        InetAddress group;
        int transferPort = FileTransferService.DEFAULT_PORT;
        List<InetSocketAddress> seeds = new ArrayList<>();
        boolean p2p = true;
        boolean autoload = true;
        URI apiBase = GeminiStreamEngine.DEFAULT_BASE_URI;
        boolean verbose;
        boolean help;
        boolean hashPassphrase;
        List<String> commands = new ArrayList<>();

        static Options parse(String[] args) {
            Options o = new Options();
            String home = System.getenv("DWB_HOME");
            o.store = home != null && !home.isBlank() ? Path.of(home) : Path.of(System.getProperty("user.home"), ".docbench");
            o.name = defaultName();
            String envModel = System.getenv("DWB_MODEL");
            o.model = envModel == null ? ModelProfile.DEFAULT : ModelProfile.parse(envModel)
                    .orElseThrow(() -> new IllegalArgumentException("DWB_MODEL: unknown model " + envModel));
            String envBase = System.getenv("GEMINI_API_BASE");
            if (envBase != null && !envBase.isBlank()) {
                o.apiBase = URI.create(envBase.strip());
            }
            String group = PeerDiscovery.DEFAULT_GROUP;
            Set<String> valued = Set.of("--store", "--name", "--model", "--discovery-port", "--transfer-port",
                    "--group", "--seed", "--api-base", "-c");
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                String v = null;
                int eq = a.indexOf('=');
                if (a.startsWith("--") && eq > 0) {
                    v = a.substring(eq + 1);
                    a = a.substring(0, eq);
                }
                if (valued.contains(a) && v == null) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(a + " needs a value");
                    }
                    v = args[++i];
                } else if (!valued.contains(a) && v != null) {
                    throw new IllegalArgumentException(a + " takes no value");
                }
                String value = v;
                switch (a) {
                    case "--help", "-h" -> o.help = true;
                    case "--hash-passphrase" -> o.hashPassphrase = true;
                    case "--verbose", "-v" -> o.verbose = true;
                    case "--no-p2p" -> o.p2p = false;
                    case "--no-autoload" -> o.autoload = false;
                    case "--store" -> o.store = Path.of(value);
                    case "--name" -> o.name = value;
                    case "--model" -> o.model = ModelProfile.parse(value).orElseThrow(() -> new IllegalArgumentException(
                            "unknown model '" + value + "'; available: " + ModelProfile.ids()));
                    case "--discovery-port" -> o.discoveryPort = port(value, false);
                    case "--transfer-port" -> o.transferPort = port(value, true);
                    case "--group" -> group = value;
                    case "--seed" -> o.seeds.add(endpoint(value));
                    case "--api-base" -> o.apiBase = URI.create(value);
                    case "-c" -> o.commands.add(value);
                    default -> throw new IllegalArgumentException("unknown option " + a);
                }
            }
            try {
                o.group = InetAddress.getByName(group);
            } catch (IOException e) {
                throw new IllegalArgumentException("invalid multicast group " + group);
            }
            if (!o.group.isMulticastAddress()) {
                throw new IllegalArgumentException(group + " is not a multicast address");
            }
            return o;
        }

        private static int port(String value, boolean allowZero) {
            int p;
            try {
                p = Integer.parseInt(value.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + value + "' is not a port number");
            }
            if (p < (allowZero ? 0 : 1) || p > 65_535) {
                throw new IllegalArgumentException("port " + p + " out of range");
            }
            return p;
        }

        private static InetSocketAddress endpoint(String value) {
            int colon = value.lastIndexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("seed must be HOST:PORT, got " + value);
            }
            return new InetSocketAddress(value.substring(0, colon).replace("[", "").replace("]", ""),
                    port(value.substring(colon + 1), false));
        }

        private static String defaultName() {
            for (String env : new String[]{"DWB_NAME", "HOSTNAME", "COMPUTERNAME"}) {
                String v = System.getenv(env);
                if (v != null && !v.isBlank()) {
                    return v.strip();
                }
            }
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                return "node";
            }
        }
    }
}
