package org.example.workbench;

import org.example.admin.AdminControlEngine;
import org.example.admin.AuditLog;
import org.example.admin.CentralPolicy;
import org.example.admin.AuditLog.Category;
import org.example.admin.AuditLog.Level;
import org.example.core.AnswerModel;
import org.example.core.AnswerModel.AnswerException;
import org.example.core.AnswerModel.TokenSink;
import org.example.core.DocumentIngestor;
import org.example.core.QuotaGate;
import org.example.core.Workbench;
import org.example.index.InvertedIndex;
import org.example.input.KeyMapRegistry.Action;
import org.example.model.SearchResult;
import org.example.net.WebSearchBridge;
import org.example.net.WebSearchBridge.HybridOutcome;
import org.example.p2p.AccessList;
import org.example.p2p.DeviceIdentity;
import org.example.p2p.DeviceRole;
import org.example.p2p.FileTransferService;
import org.example.p2p.LanSyncService;
import org.example.p2p.TrustStore;
import org.example.p2p.ZeroTrust;
import org.example.p2p.PeerInfo;
import org.example.platform.OsShellBridge;
import org.example.preview.PreviewDocument;
import org.example.preview.PreviewDocument.PreviewLine;
import org.example.preview.PreviewResult;
import org.example.preview.PreviewService;
import org.example.project.ProjectProfile;
import org.example.project.ProjectWorkspaceManager;
import org.example.project.ProjectWorkspaceManager.ActiveProject;
import org.example.project.ProjectWorkspaceManager.SwitchException;
import org.example.project.ProjectWorkspaceManager.SwitchReport;
import org.example.repl.InternalTerminalEngine;
import org.example.repl.InternalTerminalEngine.CommandSpec;
import org.example.repl.InternalTerminalEngine.Invocation;
import org.example.repl.InternalTerminalEngine.Output;
import org.example.state.FocusModeCoordinator;
import org.example.state.FocusModeCoordinator.Severity;
import org.example.state.Subscription;
import org.example.state.ViewModeCoordinator.ViewMode;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Top-level orchestrator for features 6–10, layered over the per-project {@link WorkbenchController}.
 *
 * <p>Long-lived, workspace-wide services live here: the {@link PreviewService}, the {@link AdminControlEngine}
 * (policy, limits, access control, {@code audit.log}), the {@link WebSearchBridge} and the
 * {@link ProjectWorkspaceManager}. Whenever a project becomes active this class <em>attaches</em> to its controller —
 * installs the admin ingest policy and audit recorder, registers the extra terminal commands, binds the
 * {@code TOGGLE_FOCUS} / {@code PREVIEW_SELECTED} shortcuts and creates a {@link FocusModeCoordinator} over that
 * controller's panel and view state — and detaches again before the project is unloaded, so nothing from one
 * project leaks into the next.</p>
 *
 * <p>Terminal commands added to every project terminal: {@code preview}, {@code focus}, {@code @web} (alias
 * {@code web}), {@code project}, {@code project-switch}, {@code lan}, plus the admin set ({@code admin},
 * {@code audit}, {@code blacklist}, {@code limit}, {@code gc}).</p>
 *
 * <p>LAN sync ({@link #enableLanSync}) is opt-in per process: the desktop app turns it on, headless callers and the
 * test runners do not. When on, the active project gets a {@link LanSyncService} on activation and loses it before
 * it is unloaded.</p>
 */
public final class ExtendedWorkbenchController implements AutoCloseable {

    /**
     * @param workspaceHome  holds {@code projects/}, {@code admin.properties}, {@code audit.log}, recent projects
     * @param defaultProject project created/opened on first start
     * @param watchDebounce  quiet period before a changed source is re-indexed
     */
    public record Config(Path workspaceHome, String defaultProject, PreviewService.Limits previewLimits,
                         WebSearchBridge.Config web, Duration watchDebounce, boolean gcOnSwitch) {
        public Config {
            Objects.requireNonNull(workspaceHome, "workspaceHome must not be null");
            defaultProject = defaultProject == null || defaultProject.isBlank() ? "Default" : defaultProject;
            previewLimits = previewLimits == null ? PreviewService.Limits.defaults() : previewLimits;
            web = web == null ? WebSearchBridge.Config.disabled() : web;
            watchDebounce = watchDebounce == null ? Duration.ofMillis(750) : watchDebounce;
        }

        public static Config defaults(Path workspaceHome) {
            return defaults(workspaceHome, CentralPolicy.none());
        }

        /** Web search as the central policy allows and configures it (endpoint, provider, key), else as before. */
        public static Config defaults(Path workspaceHome, CentralPolicy policy) {
            WebSearchBridge.Config web = policy.webAllowed()
                    ? WebSearchBridge.Config.fromEnvironment(policy.environment(System::getenv))
                    : WebSearchBridge.Config.disabled();
            return new Config(workspaceHome, "Default", null, web, null, true);
        }
    }

    /**
     * Shared engine services every project controller is built from.
     *
     * @param shells       a fresh shell bridge per project controller (controllers close theirs on unload)
     * @param webTransport {@code null} for the real {@link java.net.http.HttpClient}
     * @param adminToken   optional deployment token accepted by {@code admin unlock}
     */
    public record Services(DocumentIngestor ingestor, QuotaGate quota, Supplier<Optional<AnswerModel>> model,
                           Supplier<OsShellBridge> shells, WebSearchBridge.Transport webTransport, Clock clock,
                           String adminToken, CentralPolicy policy) {
        public Services {
            Objects.requireNonNull(ingestor);
            quota = quota == null ? QuotaGate.unlimited() : quota;
            policy = policy == null ? CentralPolicy.none() : policy;
            CentralPolicy p = policy;
            Supplier<Optional<AnswerModel>> m = model == null ? Optional::empty : model;
            // AI switched off by IT: no model, whatever the environment provides.
            model = p.aiAllowed() ? m : Optional::empty;
            shells = shells == null ? OsShellBridge::new : shells;
            clock = clock == null ? Clock.systemUTC() : clock;
        }

        /** Without a central policy (every setting under local control). */
        public Services(DocumentIngestor ingestor, QuotaGate quota, Supplier<Optional<AnswerModel>> model,
                        Supplier<OsShellBridge> shells, WebSearchBridge.Transport webTransport, Clock clock,
                        String adminToken) {
            this(ingestor, quota, model, shells, webTransport, clock, adminToken, CentralPolicy.none());
        }
    }

    /** What {@link #submitQuery} did with an input line. */
    public sealed interface QueryOutcome permits QueryOutcome.Local, QueryOutcome.Web, QueryOutcome.Answer {
        record Local(SearchResult result) implements QueryOutcome {
        }

        record Web(HybridOutcome outcome) implements QueryOutcome {
        }

        record Answer(Workbench.AskOutcome outcome) implements QueryOutcome {
        }
    }

    private static final int PREVIEW_LINES = 40;

    private final Config config;
    private final Services services;
    private final AdminControlEngine admin;
    private final PreviewService preview;
    private final WebSearchBridge web;
    private final ProjectWorkspaceManager projects;
    private final List<Subscription> attachment = new ArrayList<>();
    private volatile FocusModeCoordinator focus;
    private volatile Consumer<String> notifier = s -> { };

    public ExtendedWorkbenchController(Config config, Services services) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.services = Objects.requireNonNull(services, "services must not be null");
        Path home = config.workspaceHome().toAbsolutePath().normalize();
        this.admin = new AdminControlEngine(home.resolve("admin.properties"), new AuditLog(home.resolve("audit.log")),
                services.clock(), services.adminToken(), services.policy());
        this.preview = new PreviewService(config.previewLimits());
        this.preview.setObserver(this::auditPreview);
        this.web = new WebSearchBridge(config.web(), services.webTransport(),
                () -> controller().workbench().index(), services.quota(), services.model(), services.clock());
        this.projects = new ProjectWorkspaceManager(home, this::createController, config.gcOnSwitch(),
                config.watchDebounce());
        projects.onActivated(this::attach);
        projects.onDeactivating(this::detach);
    }

    private WorkbenchController createController(ProjectProfile profile) {
        Workbench workbench = new Workbench(services.ingestor(), new InvertedIndex(),
                services.model().get().orElse(null), services.quota(), Workbench.DEFAULT_MIN_SCORE);
        return new WorkbenchController(workbench, profile.controllerConfig(), services.shells().get());
    }

    /**
     * Loads the admin policy and opens the default (or most recent) project, creating it on first start.
     */
    public SwitchReport start() throws IOException, SwitchException {
        List<String> problems = admin.load();
        for (String p : problems) {
            admin.audit().record(Level.WARN, Category.ADMIN, "op", "load", "problem", p);
        }
        Optional<ProjectProfile> target = projects.find(config.defaultProject()).or(projects::mostRecent);
        ProjectProfile profile = target.isPresent() ? target.get() : projects.create(config.defaultProject(), null);
        SwitchReport report = projects.switchTo(profile);
        services.policy().reportFolder().ifPresent(folder -> {
            if (reporter == null) {
                reporter = new HealthReporter(this, folder, services.policy().reportIntervalMinutes());
            }
        });
        return report;
    }

    // ================================================================== LAN sync

    /** The user's own LAN switch ({@code lan --on/--off}), in the workspace so it holds for every project. */
    static final String LAN_SETTINGS_FILE = "lan.properties";

    private volatile LanSyncService.Settings lanSettings;
    /** Why LAN sync is not running, or {@code null} when it is allowed and switched on. */
    private volatile String lanOff = "not enabled in this process";
    private volatile LanSyncService lan;
    private record ShownFailure(String message, long atMillis) {
    }

    /** Last LAN failure toast per item: a fetch retried every minute with the same error must not toast each time. */
    private final java.util.concurrent.ConcurrentHashMap<String, ShownFailure> lanFailureShown =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LAN_FAILURE_REPEAT_MILLIS = 10 * 60_000L;

    /**
     * Turns LAN sync on for every project this controller activates. The central policy wins ({@code lan.enabled},
     * {@code lan.requireSecret}), then the user's {@code lan --off}. Call before {@link #start()}.
     */
    public void enableLanSync(LanSyncService.Settings settings) {
        Objects.requireNonNull(settings, "settings must not be null");
        CentralPolicy p = services.policy();
        if (!p.lanAllowed()) {
            lanOff = "turned off by the central IT policy";
            return;
        }
        if (p.lanRequiresSecret() && !settings.authenticated()) {
            lanOff = "the central IT policy requires authenticated peers (zero-trust mode, or DWB_SECRET in legacy"
                    + " mode)";
            return;
        }
        lanSettings = settings;
        lanOff = lanSwitchedOn() ? null : "switched off on this computer (lan --on)";
        if (settings.trust() != null) {
            // Pairings with this device's PIN, whether the PIN came from the terminal or the admin panel.
            settings.trust().onPairing(new ZeroTrust.PairingListener() {
                @Override
                public void paired(TrustStore.Device device, String code) {
                    admin.audit().record(Level.INFO, Category.ADMIN, "op", "lan-paired", "device", device.fingerprint(),
                            "name", device.name(), "role", device.role().name());
                    ExtendedWorkbenchController.this.notify(Severity.INFO, "Yeni cihaz eşleşti: " + device.name()
                            + " · doğrulama kodu " + code + " (yeni cihazdaki kodla aynı olmalı)");
                }

                @Override
                public void failed(String reason) {
                    admin.audit().record(Level.WARN, Category.ADMIN, "op", "lan-pair-failed", "msg", reason);
                    ExtendedWorkbenchController.this.notify(Severity.WARNING, "Eşleştirme başarısız: " + reason);
                }
            });
        }
    }

    /** The running sync of the active project, if any. */
    public Optional<LanSyncService> lan() {
        return Optional.ofNullable(lan);
    }

    private void startLan(ActiveProject project) {
        LanSyncService.Settings settings = lanSettings;
        if (settings == null || lanOff != null) {
            return;
        }
        LanSyncService service;
        try {
            service = new LanSyncService(settings, project.controller(), project.profile().root(), lanListener());
        } catch (IOException e) {
            notify(Severity.WARNING, "LAN eşitleme başlatılamadı: " + e.getMessage());
            return;
        }
        synchronized (this) {
            stopLan();
            lan = service;
        }
        // Binding sockets and announcing the catalog stats every indexed file: never on the activating thread.
        Thread.ofVirtual().name("dwb-lan-start").start(() -> {
            try {
                service.start();
                if (!service.running()) {
                    return; // closed again before it started (project switch)
                }
                LanSyncService.Status st = service.status();
                admin.audit().record(Level.INFO, Category.PROJECT, "op", "lan-start", "node", st.nodeName(),
                        "tcp", String.valueOf(st.transferPort()), "mode", st.mode());
                if (!st.authenticated()) {
                    notify(Severity.INFO, "LAN eşitleme açık (kimlik doğrulamasız): bu ağdaki Document Workbench"
                            + " düğümleri belgeleri paylaşır. Kısıtlamak için DWB_TRUST=legacy ayarını kaldırın"
                            + " (sıfır güven modu) ya da tüm düğümlerde DWB_SECRET ayarlayın.");
                } else if (service.zeroTrust().isPresent() && service.zeroTrust().get().trust().devices().isEmpty()) {
                    notify(Severity.INFO, "LAN sıfır güven modunda: bu cihaz henüz hiçbir cihazla eşleşmedi ve"
                            + " yalıtılmış durumda. Eşleştirmek için terminalde: lan-pair --new");
                }
            } catch (IOException | RuntimeException e) {
                admin.audit().record(Level.WARN, Category.RUNTIME_ERROR, "op", "lan-start", "msg",
                        String.valueOf(e.getMessage()));
                notify(Severity.WARNING, "LAN eşitleme başlatılamadı: " + e.getMessage());
            }
        });
    }

    private synchronized void stopLan() {
        LanSyncService s = lan;
        lan = null;
        if (s != null) {
            s.close();
        }
    }

    private LanSyncService.Listener lanListener() {
        return new LanSyncService.Listener() {
            @Override
            public void peerJoined(PeerInfo peer) {
                admin.audit().record(Level.INFO, Category.PROJECT, "op", "lan-peer", "peer", peer.name(),
                        "address", peer.address().getHostAddress());
                ExtendedWorkbenchController.this.notify(Severity.INFO,
                        "LAN: " + peer.name() + " (" + peer.address().getHostAddress() + ") bağlandı");
            }

            @Override
            public void received(List<String> names) {
                admin.audit().record(Level.INFO, Category.FILE_ACCESS, "op", "lan-receive", "documents",
                        String.valueOf(names.size()));
                ExtendedWorkbenchController.this.notify(Severity.INFO, names.size() == 1
                        ? "LAN: “" + names.getFirst() + "” alındı ve indekslendi"
                        : "LAN: " + names.size() + " belge alındı ve indekslendi");
            }

            @Override
            public void failed(String what, String reason) {
                admin.audit().record(Level.WARN, Category.INGEST_FAILURE, "op", "lan-receive", "file", what,
                        "msg", String.valueOf(reason));
                String message = "LAN: “" + what + "” alınamadı: " + reason;
                long now = System.currentTimeMillis();
                ShownFailure previous = lanFailureShown.get(what);
                if (previous != null && previous.message().equals(message)
                        && now - previous.atMillis() < LAN_FAILURE_REPEAT_MILLIS) {
                    return; // same failure as the last retry: already on screen recently (the audit log has each one)
                }
                lanFailureShown.put(what, new ShownFailure(message, now));
                ExtendedWorkbenchController.this.notify(Severity.WARNING, message);
            }

            @Override
            public void transferStarted(String what, String peer) {
                ExtendedWorkbenchController.this.notify(Severity.INFO, "LAN: " + what + " " + peer + " düğümünden alınıyor…");
            }

            @Override
            public void transferProgress(String what, String peer, long transferred, long total) {
                ExtendedWorkbenchController.this.notify(Severity.INFO, String.format(java.util.Locale.ROOT,
                        "LAN: %s alınıyor… %%%d (%.1f / %.1f GB)", what, transferred * 100 / total,
                        transferred / 1e9, total / 1e9));
            }

            @Override
            public void transferTick(String what, String peer, long transferred, long total) {
                transfers.progress(what, peer, transferred, total);
            }

            @Override
            public void transferFinished(String what, String peer, boolean verified) {
                transfers.finished(what, peer, verified);
            }

            @Override
            public void transferCompleted(boolean outgoing, String name, String peer, long bytes, boolean encrypted) {
                if (outgoing) {
                    admin.audit().record(Level.INFO, Category.FILE_ACCESS, "op", "lan-send", "file", name, "peer", peer,
                            "bytes", String.valueOf(bytes), "channel", encrypted ? "aes-256-gcm" : "plain");
                }
                transfers.completed(outgoing, name, peer, bytes, encrypted);
            }
        };
    }

    /**
     * UI sink for the live state of large LAN downloads (a persistent indicator, not a notification: quiet mode does
     * not gate it). Called on transfer threads: hand off, never block.
     */
    public interface TransferObserver {
        TransferObserver NONE = new TransferObserver() {
            @Override
            public void progress(String what, String peer, long transferred, long total) {
            }

            @Override
            public void finished(String what, String peer, boolean verified) {
            }
        };

        /** {@code transferred == total}: every byte arrived; SHA-256 verification and delivery follow. */
        void progress(String what, String peer, long transferred, long total);

        /** The download is over (verified and delivered, or broken off); the indicator goes away. */
        void finished(String what, String peer, boolean verified);

        /**
         * A transfer of any size finished and was verified, in either direction; {@code encrypted} when it went through
         * the zero-trust AES-GCM tunnel.
         */
        default void completed(boolean outgoing, String name, String peer, long bytes, boolean encrypted) {
        }
    }

    private volatile TransferObserver transfers = TransferObserver.NONE;

    public void setTransferObserver(TransferObserver observer) {
        this.transfers = observer == null ? TransferObserver.NONE : observer;
    }

    private boolean lanSwitchedOn() {
        Path file = config.workspaceHome().resolve(LAN_SETTINGS_FILE);
        if (!Files.isRegularFile(file)) {
            return true;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | RuntimeException e) {
            return true;
        }
        return !"false".equalsIgnoreCase(p.getProperty("enabled", "true").strip());
    }

    private void saveLanSwitch(boolean on) throws IOException {
        Properties p = new Properties();
        p.setProperty("enabled", String.valueOf(on));
        Files.createDirectories(config.workspaceHome());
        try (Writer out = Files.newBufferedWriter(config.workspaceHome().resolve(LAN_SETTINGS_FILE),
                StandardCharsets.UTF_8)) {
            p.store(out, "Document Workbench - LAN sync switch");
        }
    }

    private void cmdLan(Invocation inv, Output out) throws IOException {
        if (inv.has("on") || inv.has("off")) {
            boolean on = inv.has("on");
            if (lanSettings == null) {
                throw new IllegalArgumentException("LAN sync is " + lanOff);
            }
            saveLanSwitch(on);
            if (on) {
                lanOff = null;
                if (lan == null) {
                    projects.active().ifPresent(this::startLan);
                }
                out.println("LAN sync: on");
            } else {
                lanOff = "switched off on this computer (lan --on)";
                stopLan();
                out.println("LAN sync: off");
            }
            return;
        }
        LanSyncService s = lan;
        if (s == null || !s.running()) {
            out.println("LAN sync: " + (s == null ? "off - " + lanOff : "starting…"));
            return;
        }
        if (inv.has("sync")) {
            s.syncNow();
            out.println("re-checking every peer's catalog");
        }
        LanSyncService.Status st = s.status();
        out.printf("LAN sync: on · node %s (%s) · tcp/%d · discovery on %s · %s", st.nodeName(), st.nodeId(),
                st.transferPort(), st.interfaces().isEmpty() ? "seeds only" : String.join(", ", st.interfaces()),
                st.mode());
        s.zeroTrust().ifPresent(z -> out.printf("device %s · %d trusted device(s) · %d unpaired seen · lan-pair,"
                        + " lan-devices, lan-acl manage trust", DeviceIdentity.display(z.identity().fingerprint()),
                z.trust().devices().size(), z.unpaired().size()));
        if (!st.authenticated()) {
            out.println("  WARNING: anyone on this network segment can download the documents indexed here and"
                    + " send new ones; remove DWB_TRUST=legacy (zero-trust mode) or set DWB_SECRET on every node");
        }
        out.printf("peers (%d):", st.peers().size());
        for (PeerInfo p : st.peers()) {
            out.printf("  %-20s %-15s %d document(s)%s", p.name(), p.address().getHostAddress(), p.advertised(),
                    p.catalogComplete() ? "" : " (catalog incomplete)");
        }
        out.printf("shared: %d · received this session: %d · pending: %d · declined: %d", st.shared(), st.received(),
                st.pending(), st.declined());
        out.println("inbox: " + s.inbox());
    }

    // ================================================================== IT support

    private volatile HealthReporter reporter;

    /** The shared-folder health reporter, when the central policy configures one. */
    public Optional<HealthReporter> healthReporter() {
        return Optional.ofNullable(reporter);
    }

    /** A language model is available for answers (configured and allowed by policy). */
    public boolean aiConfigured() {
        return services.model().get().isPresent();
    }

    public CentralPolicy policy() {
        return services.policy();
    }

    private void cmdSupportBundle(Invocation inv, Output out) throws IOException {
        Path folder = inv.options().containsKey("out") ? Path.of(inv.option("out", "")).toAbsolutePath()
                : Diagnostics.defaultBundleFolder(this);
        Path zip = Diagnostics.writeSupportBundle(this, folder);
        out.println("support bundle written: " + zip);
        out.println("it contains version, memory, policy, index health and recent audit entries (search text"
                + " redacted); no document contents");
    }

    private void cmdHealthReport(Invocation inv, Output out) throws IOException {
        HealthReporter r = reporter;
        if (r == null) {
            out.println("no report folder is configured (central policy key report.folder)");
            return;
        }
        if (inv.flags().contains("now")) {
            out.println("written: " + r.reportNow());
            return;
        }
        out.println("report folder: " + r.folder() + " · every " + services.policy().reportIntervalMinutes() + " min");
        out.println(r.lastWritten() == null ? "not written yet" : "last written: " + r.lastWritten());
        if (r.lastProblem() != null) {
            out.println("last problem: " + r.lastProblem());
        }
    }

    // ================================================================== accessors (UI binding points)

    /** The active project's controller; throws before {@link #start()}. */
    public WorkbenchController controller() {
        return projects.active().map(ActiveProject::controller)
                .orElseThrow(() -> new IllegalStateException("no active project"));
    }

    public InternalTerminalEngine terminal() {
        return controller().terminal();
    }

    public FocusModeCoordinator focus() {
        FocusModeCoordinator f = focus;
        if (f == null) {
            throw new IllegalStateException("no active project");
        }
        return f;
    }

    public PreviewService preview() {
        return preview;
    }

    public AdminControlEngine admin() {
        return admin;
    }

    public WebSearchBridge web() {
        return web;
    }

    public ProjectWorkspaceManager projects() {
        return projects;
    }

    /** UI sink for user notifications (toasts); gated by quiet mode. */
    public void setNotifier(Consumer<String> notifier) {
        this.notifier = notifier == null ? s -> { } : notifier;
    }

    /** Shows a notification unless quiet mode suppresses it; critical ones always pass. */
    public boolean notify(Severity severity, String message) {
        FocusModeCoordinator f = focus;
        if (f != null && !f.allowNotification(severity)) {
            return false;
        }
        notifier.accept(message);
        return true;
    }

    // ================================================================== attach / detach

    private void attach(ActiveProject project) {
        WorkbenchController c = project.controller();
        synchronized (attachment) {
            c.setIngestPolicy(admin.ingestPolicy());
            attachment.add(c.onEvent(admin.eventRecorder()));
            attachment.add(c.onLongHash(label -> notify(Severity.INFO, "SHA-256 doğrulanıyor: " + label + "…")));
            admin.registerCommands(c.terminal(), () -> c);
            c.terminal().markSensitive("admin", 1);
            registerCommands(c);
            FocusModeCoordinator f = new FocusModeCoordinator(c.panels(), c.view());
            focus = f;
            attachment.add(c.overrideAction(Action.TOGGLE_FOCUS, () -> {
                f.toggle();
                c.terminal().output().println("focus: " + (f.focusActive() ? "on" : "off"));
            }));
            attachment.add(c.overrideAction(Action.PREVIEW_SELECTED, () -> preview.previewAsync(
                    c.sourceOf(c.selectedRow()).orElse(null)).thenAccept(r -> {
                        if (!(r instanceof PreviewResult.Ready)) {
                            notify(Severity.INFO, r.describe());
                        }
                    })));
        }
        admin.audit().record(Level.INFO, Category.PROJECT, "op", "activate", "project", project.profile().name(),
                "root", project.profile().root().toString());
        startLan(project);
    }

    private void detach(ActiveProject project) {
        stopLan(); // before the controller is unloaded: nothing may be ingested into a closing project
        synchronized (attachment) {
            FocusModeCoordinator f = focus;
            if (f != null) {
                f.exit();
                long suppressed = f.drainSuppressedNotifications();
                if (suppressed > 0) {
                    notifier.accept(suppressed + " notification(s) were suppressed in quiet mode");
                }
            }
            focus = null;
            preview.dismiss();
            attachment.forEach(Subscription::close);
            attachment.clear();
        }
        admin.audit().record(Level.INFO, Category.PROJECT, "op", "deactivate", "project", project.profile().name());
    }

    // ================================================================== query routing

    /**
     * Routes one search-bar line: {@code @web …} → metered web bridge, {@code ? …} → grounded local answer,
     * anything else → local BM25 search published to the view model.
     */
    public QueryOutcome submitQuery(String input, TokenSink sink) throws AnswerException {
        String line = input == null ? "" : input.strip();
        WorkbenchController c = controller();
        if (WebSearchBridge.isWebQuery(line) && !services.policy().webAllowed()) {
            // Turned off by IT: say so, and still answer from local documents like any other web downgrade.
            String question = WebSearchBridge.stripPrefix(line);
            SearchResult local = c.search(question.isBlank() ? line : question, 10);
            HybridOutcome outcome = new HybridOutcome.LocalOnly(question, local,
                    WebSearchBridge.Downgrade.NOT_CONFIGURED, "web search is turned off by your organization's IT policy");
            auditWeb(outcome);
            return new QueryOutcome.Web(outcome);
        }
        if (WebSearchBridge.isWebQuery(line)) {
            HybridOutcome outcome = web.ask(line, sink);
            auditWeb(outcome);
            if (outcome instanceof HybridOutcome.LocalOnly local) {
                c.view().publish(local.local());
            }
            return new QueryOutcome.Web(outcome);
        }
        if (line.startsWith("?")) {
            requireAiAllowed();
            return new QueryOutcome.Answer(c.workbench().ask(line.substring(1), sink));
        }
        return new QueryOutcome.Local(c.search(line, 20));
    }

    private void auditWeb(HybridOutcome outcome) {
        String query = outcome.question().length() > 200 ? outcome.question().substring(0, 200) : outcome.question();
        switch (outcome) {
            case HybridOutcome.Answered a -> {
                admin.audit().record(Level.INFO, Category.WEB_QUERY, "query", query, "outcome", "answered",
                        "kept", a.distillation().kept() + "/" + a.distillation().fetched());
                admin.audit().record(Level.INFO, Category.AI_CALL, "model", a.stats().model(), "promptTokens",
                        String.valueOf(a.stats().promptTokens()), "outputTokens",
                        String.valueOf(a.stats().outputTokens()));
            }
            case HybridOutcome.SnippetsOnly s -> admin.audit().record(Level.INFO, Category.WEB_QUERY, "query", query,
                    "outcome", "snippets", "kept", s.distillation().kept() + "/" + s.distillation().fetched());
            case HybridOutcome.LocalOnly l -> admin.audit().record(Level.WARN, Category.WEB_QUERY, "query", query,
                    "outcome", "local-only", "reason", l.reason().name(), "detail", l.detail());
        }
    }

    private void auditPreview(PreviewResult result) {
        String file = String.valueOf(result.path());
        switch (result) {
            case PreviewResult.Ready r -> {
                admin.audit().record(Level.INFO, Category.PREVIEW, "file", file, "result",
                        "READY", "lines", String.valueOf(r.document().lineCount()));
                projects.active().ifPresent(a -> a.controller().noteRecent(r.path(), "preview"));
            }
            case PreviewResult.TooLarge t -> admin.audit().record(Level.INFO, Category.PREVIEW, "file", file,
                    "result", "TOO_LARGE", "bytes", String.valueOf(t.sizeBytes()), "limit",
                    String.valueOf(t.limitBytes()));
            case PreviewResult.Failed f -> admin.audit().record(Level.WARN, Category.PREVIEW, "file", file, "result",
                    "FAILED", "reason", f.reason().name(), "msg", f.message());
            case PreviewResult.Unsupported u -> admin.audit().record(Level.INFO, Category.PREVIEW, "file", file,
                    "result", "UNSUPPORTED");
            case PreviewResult.Superseded s -> {
                // not an access; nothing was shown
            }
        }
    }

    // ================================================================== terminal commands

    private void registerCommands(WorkbenchController c) {
        InternalTerminalEngine t = c.terminal();
        t.register(new CommandSpec("preview", "preview <row|path|id> [--lines N] | --close | --limits",
                "Inline, size-guarded document preview", Set.of("lines"), (inv, out) -> cmdPreview(c, inv, out)));
        t.register(new CommandSpec("focus",
                "focus [--on | --off | --toggle] [--quiet on|off] [--notifications user_actions_only|all]",
                "Distraction-free mode, quiet mode and what quiet mode still lets through",
                Set.of("quiet", "notifications"), this::cmdFocus));
        t.register(new CommandSpec("@web", "@web <question>",
                "Metered web search, BM25-distilled before the AI sees it", Set.of(), (inv, out) -> cmdWeb(c, inv, out)));
        t.alias("web", "@web");
        t.register(new CommandSpec("ask", "ask [--dry-run] [--only <file|folder|id>] <question>",
                "Grounded answer from your documents; --dry-run shows what would be sent, without calling the model",
                Set.of("only"), (inv, out) -> cmdAsk(c, inv, out)));
        t.register(new CommandSpec("support-bundle", "support-bundle [--out DIR]",
                "Write a zip for IT: version, memory, policy, index health, recent audit (no document contents)",
                Set.of("out"), this::cmdSupportBundle));
        t.register(new CommandSpec("health-report", "health-report [--now]",
                "Status of the shared-folder health report IT configured; --now writes it immediately", Set.of(),
                this::cmdHealthReport));
        t.register(new CommandSpec("watch", "watch --list",
                "Files the project watches for changes, by folder, with their last change", Set.of(),
                (inv, out) -> cmdWatch(c, inv, out)));
        t.register(new CommandSpec("project", "project [--list | --info | --save | --new <name> [--dir <path>]]",
                "Manage isolated projects", Set.of("new", "dir"), this::cmdProject));
        t.register(new CommandSpec("project-switch", "project-switch <name|id|path>",
                "Save this project and switch to another without restarting", Set.of(), this::cmdSwitch));
        t.register(new CommandSpec("lan", "lan [--sync | --on | --off]",
                "LAN sync with other Document Workbench nodes: status, peers, re-check now, switch on/off",
                Set.of(), this::cmdLan));
        t.register(new CommandSpec("lan-pair", "lan-pair --new [--guest] [--dept NAME] | lan-pair <peer|host:port> <PIN>",
                "Zero-trust onboarding: open a 5-minute one-time PIN here, or pair with a device showing one",
                Set.of("dept"), (inv, out) -> cmdLanPair(inv, out)));
        c.terminal().markSensitive("lan-pair", 1);
        t.register(new CommandSpec("lan-devices", "lan-devices [--pending | --remove <device> | --role <device> "
                + "FULL_PEER|RESTRICTED_GUEST | --dept <device> <NAME|->]",
                "Zero-trust: trusted devices with role and department, unpaired devices seen, changes",
                Set.of("remove", "role", "dept"), this::cmdLanDevices));
        t.register(new CommandSpec("lan-acl", "lan-acl <hash|name> [--grant <device|dept:NAME>] [--revoke <entry>]",
                "Zero-trust: which devices may see and pull one document (guests get one-shot grants)",
                Set.of("grant", "revoke"), (inv, out) -> cmdLanAcl(c, inv, out)));
    }

    // ------------------------------------------------------------------ zero-trust LAN: API (terminal and GUI)

    /** What the status bar shows about LAN security. */
    public enum LanShieldKind { OFF, STARTING, ZERO_TRUST, LEGACY_SECRET, LEGACY_OPEN }

    /**
     * Cheap snapshot of the LAN security state (no disk or network access; safe to poll from the FX thread).
     *
     * @param fingerprint this device's fingerprint in zero-trust mode, empty otherwise
     * @param trusted     number of trusted devices (zero-trust mode)
     * @param reason      why LAN sync is off, when it is
     */
    public record LanShield(LanShieldKind kind, String fingerprint, int trusted, int transferPort, String reason) {
        public boolean encrypted() {
            return kind == LanShieldKind.ZERO_TRUST;
        }
    }

    public LanShield lanShield() {
        LanSyncService.Settings settings = lanSettings;
        LanSyncService s = lan;
        ZeroTrust z = settings == null ? null : settings.trust();
        String fp = z == null ? "" : z.identity().fingerprint();
        int trusted = z == null ? 0 : z.trust().devices().size();
        if (settings == null || lanOff != null || s == null) {
            return new LanShield(LanShieldKind.OFF, fp, trusted, 0, lanOff == null ? "not started" : lanOff);
        }
        if (!s.running()) {
            return new LanShield(LanShieldKind.STARTING, fp, trusted, 0, "");
        }
        LanShieldKind kind = z != null ? LanShieldKind.ZERO_TRUST
                : settings.security().enabled() ? LanShieldKind.LEGACY_SECRET : LanShieldKind.LEGACY_OPEN;
        return new LanShield(kind, fp, trusted, s.transferPort(), "");
    }

    /** The zero-trust context when LAN sync is configured in zero-trust mode (running or not). */
    public Optional<ZeroTrust> zeroTrust() {
        LanSyncService.Settings settings = lanSettings;
        return Optional.ofNullable(settings == null ? null : settings.trust());
    }

    private ZeroTrust requireZeroTrust() {
        return zeroTrust().orElseThrow(() -> new IllegalArgumentException(lanSettings == null
                ? "LAN sync is " + lanOff
                : "LAN sync runs in legacy mode (DWB_TRUST=legacy); pairing, devices and access lists exist only in"
                + " zero-trust mode"));
    }

    private LanSyncService zeroTrustLan() {
        requireZeroTrust();
        LanSyncService s = lan;
        if (s == null || !s.running()) {
            throw new IllegalArgumentException("LAN sync is " + (s == null ? "off - " + lanOff : "starting…"));
        }
        return s;
    }

    /** Operation names of the admin-locked LAN actions (as audited and shown in denials). */
    public static final String PRIV_LAN_PAIR = "lan-pair --new";
    public static final String PRIV_LAN_REVOKE = "lan-devices --remove";

    /**
     * Opens a 5-minute, one-attempt pairing PIN on this device (LAN sync must be running to accept it). Admin-locked:
     * with an admin passphrase (or deployment token) configured, the admin session must be unlocked.
     */
    public ZeroTrust.PairingTicket lanOpenPairing(DeviceRole role, String department)
            throws AdminControlEngine.PrivilegeException {
        zeroTrustLan();
        admin.requirePrivilege(PRIV_LAN_PAIR);
        ZeroTrust.PairingTicket ticket = requireZeroTrust().openPairing(role, department);
        admin.audit().record(Level.INFO, Category.ADMIN, "op", "lan-pair-open", "role", role.name(),
                "department", ticket.department());
        return ticket;
    }

    public void lanCancelPairing() {
        requireZeroTrust().cancelPairing();
    }

    /**
     * Pairs with a device showing a PIN ({@code peer}: discovered name or fingerprint prefix, or {@code host:port}).
     * Blocking (network): never on the FX thread.
     */
    public FileTransferService.PairingResult lanJoin(String peer, String pin) throws IOException {
        LanSyncService s = zeroTrustLan();
        java.net.InetSocketAddress endpoint = s.endpoint(peer.strip()).orElseThrow(() ->
                new IllegalArgumentException("unknown peer '" + peer + "'; use host:port (an unpaired device does not"
                        + " appear among the peers)"));
        FileTransferService.PairingResult r = s.pair(endpoint, pin.strip());
        admin.audit().record(Level.INFO, Category.ADMIN, "op", "lan-paired", "device", r.device().fingerprint(),
                "name", r.device().name());
        return r;
    }

    /** Pairing outcomes against this device's PIN (for the PIN dialog); run the returned action to unsubscribe. */
    public Runnable onLanPairing(ZeroTrust.PairingListener listener) {
        return requireZeroTrust().onPairing(listener);
    }

    public List<TrustStore.Device> lanDevices() {
        return zeroTrust().map(z -> z.trust().devices()).orElse(List.of());
    }

    public List<ZeroTrust.Sighting> lanUnpaired() {
        return zeroTrust().map(ZeroTrust::unpaired).orElse(List.of());
    }

    /** Whether a trusted device currently announces itself on the network. */
    public boolean lanOnline(String fingerprint) {
        LanSyncService s = lan;
        return s != null && s.running() && s.peers().stream().anyMatch(p -> p.nodeId().equals(fingerprint));
    }

    public TrustStore.Device lanSetRole(String device, DeviceRole role) throws IOException {
        ZeroTrust z = requireZeroTrust();
        TrustStore.Device d = device(z.trust(), device);
        TrustStore.Device changed = z.trust().setRole(d.fingerprint(), role);
        admin.audit().record(Level.INFO, Category.ADMIN, "op", "lan-role", "device", d.fingerprint(), "role", role.name());
        lanCatalogChanged();
        return changed;
    }

    public TrustStore.Device lanSetDepartment(String device, String department) throws IOException {
        ZeroTrust z = requireZeroTrust();
        TrustStore.Device d = device(z.trust(), device);
        TrustStore.Device changed = z.trust().setDepartment(d.fingerprint(), department);
        admin.audit().record(Level.INFO, Category.ADMIN, "op", "lan-department", "device", d.fingerprint(),
                "department", changed.department());
        lanCatalogChanged();
        return changed;
    }

    /**
     * Removes a device from the trust list (and from every access list of the active project). Admin-locked like
     * {@link #lanOpenPairing}.
     */
    public TrustStore.Device lanRevoke(String device) throws IOException, AdminControlEngine.PrivilegeException {
        ZeroTrust z = requireZeroTrust();
        TrustStore.Device d = device(z.trust(), device);
        admin.requirePrivilege(PRIV_LAN_REVOKE);
        LanSyncService s = lan;
        z.forget(d.fingerprint(), s == null ? null : s.store());
        admin.audit().record(Level.WARN, Category.ADMIN, "op", "lan-unpair", "device", d.fingerprint());
        lanCatalogChanged();
        return d;
    }

    /**
     * Undoes a pairing made moments ago because the two verification codes differed (someone was in between). Needs no
     * admin session, so a suspected interception can always be cut off at once, but it only accepts a device added
     * within the pairing window ({@link ZeroTrust#PAIRING_TTL}); established devices are removed with the admin-locked
     * {@link #lanRevoke}.
     */
    public TrustStore.Device lanRejectPairing(String fingerprint) throws IOException {
        ZeroTrust z = requireZeroTrust();
        TrustStore.Device d = z.trust().find(fingerprint).orElseThrow(() ->
                new IllegalArgumentException("not a trusted device: " + fingerprint));
        if (d.added().isBefore(java.time.Instant.now().minus(ZeroTrust.PAIRING_TTL))) {
            throw new IllegalArgumentException("only a pairing from the last 5 minutes can be rejected here; removing an"
                    + " established device needs the admin session (Güvenden Çıkar)");
        }
        LanSyncService s = lan;
        z.forget(d.fingerprint(), s == null ? null : s.store());
        admin.audit().record(Level.SECURITY, Category.ADMIN, "op", "lan-pair-rejected", "device", d.fingerprint(),
                "reason", "verification codes differed");
        lanCatalogChanged();
        return d;
    }

    /** Whether the admin-locked LAN actions can run now (no passphrase configured, or the session is unlocked). */
    public boolean lanAdminUnlocked() {
        return admin.unlocked();
    }

    /** Documents of the active project that have an access list, with readable entries. */
    public java.util.Map<String, String> lanAccessLists() {
        LanSyncService s = zeroTrustLan();
        ZeroTrust z = requireZeroTrust();
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        s.store().acl().snapshot().forEach((sha, entries) -> out.put(sha, describe(z, entries)));
        return out;
    }

    /**
     * Grants ({@code grant}) or revokes one document ({@code sha256}) for a device or {@code dept:NAME}; guests get
     * one-shot grants. Returns the document's entries afterwards, readable.
     */
    public String lanAcl(String sha256, String target, boolean grant) throws IOException {
        LanSyncService s = zeroTrustLan();
        ZeroTrust z = requireZeroTrust();
        String entry = aclEntry(z, target);
        if (grant) {
            s.store().acl().grant(sha256, entry);
        } else {
            s.store().acl().revoke(sha256, entry.startsWith("once:") ? entry.substring(5) : entry);
        }
        admin.audit().record(Level.INFO, Category.ADMIN, "op", grant ? "lan-grant" : "lan-revoke", "sha256", sha256,
                "entry", entry);
        s.catalogChanged();
        List<String> entries = s.store().acl().entries(sha256);
        return entries.isEmpty() ? "" : describe(z, entries);
    }

    private void lanCatalogChanged() {
        LanSyncService s = lan;
        if (s != null && s.running()) {
            s.catalogChanged();
        }
    }

    // ------------------------------------------------------------------ zero-trust LAN: terminal commands

    private void cmdLanPair(Invocation inv, Output out) throws IOException, AdminControlEngine.PrivilegeException {
        if (inv.has("new")) {
            DeviceRole role = inv.has("guest") ? DeviceRole.RESTRICTED_GUEST : DeviceRole.FULL_PEER;
            ZeroTrust.PairingTicket ticket = lanOpenPairing(role, inv.option("dept", ""));
            out.println("pairing PIN: " + ticket.pin() + "  (valid 5 minutes, one attempt; the new device joins as "
                    + "role=" + role + (ticket.department().isEmpty() ? "" : " department=" + ticket.department()) + ")");
            out.println("on the new device: lan-pair <this computer's address>:" + lanShield().transferPort() + " "
                    + ticket.pin() + "   · this device: "
                    + DeviceIdentity.display(requireZeroTrust().identity().fingerprint()));
            return;
        }
        if (inv.has("cancel")) {
            lanCancelPairing();
            out.println("pairing window closed");
            return;
        }
        if (inv.args().size() != 2) {
            throw new IllegalArgumentException("usage: lan-pair --new [--guest] [--dept NAME] | lan-pair <peer|host:port>"
                    + " <PIN> | lan-pair --cancel");
        }
        FileTransferService.PairingResult r = lanJoin(inv.args().get(0), inv.args().get(1));
        out.println("paired with " + r.device().name() + " (" + DeviceIdentity.display(r.device().fingerprint())
                + "), trusted as " + r.device().labels());
        out.println("verification code: " + r.verificationCode() + " - the other device must show the same code;"
                + " if it does not, run lan-devices --remove " + r.device().fingerprint().substring(0, 12));
    }

    private void cmdLanDevices(Invocation inv, Output out) throws IOException, AdminControlEngine.PrivilegeException {
        zeroTrustLan();
        ZeroTrust z = requireZeroTrust();
        if (inv.has("remove")) {
            TrustStore.Device d = lanRevoke(inv.option("remove", ""));
            out.println("no longer trusted: " + d.name() + " (" + DeviceIdentity.display(d.fingerprint()) + ")");
            return;
        }
        if (inv.has("role")) {
            DeviceRole role = DeviceRole.parse(inv.args().isEmpty() ? "" : inv.args().getFirst()).orElseThrow(() ->
                    new IllegalArgumentException("role must be FULL_PEER or RESTRICTED_GUEST"));
            TrustStore.Device changed = lanSetRole(inv.option("role", ""), role);
            out.println(changed.name() + ": " + changed.labels());
            return;
        }
        if (inv.has("dept")) {
            TrustStore.Device changed = lanSetDepartment(inv.option("dept", ""),
                    inv.args().isEmpty() ? "" : inv.args().getFirst());
            out.println(changed.name() + ": " + changed.labels());
            return;
        }
        if (inv.has("pending")) {
            List<ZeroTrust.Sighting> seen = lanUnpaired();
            out.println(seen.isEmpty() ? "no unpaired device announced itself in the last 10 minutes"
                    : "unpaired devices (isolated until paired with lan-pair):");
            seen.forEach(p -> out.printf("  %-20s %s  %s", p.name(), DeviceIdentity.display(p.fingerprint()),
                    p.address().getHostAddress()));
            return;
        }
        out.println("this device: " + DeviceIdentity.display(z.identity().fingerprint()) + "  ("
                + z.identity().fingerprint() + ")");
        List<TrustStore.Device> all = lanDevices();
        out.println(all.isEmpty() ? "no trusted devices yet: this node is isolated (lan-pair --new)"
                : "trusted devices (" + all.size() + "):");
        for (TrustStore.Device d : all) {
            out.printf("  %-20s %s  %s", d.name(), DeviceIdentity.display(d.fingerprint()), d.labels());
        }
        z.pendingPairing().ifPresent(tk -> out.println("pairing window open until " + tk.expires() + " (" + tk.role() + ")"));
    }

    private void cmdLanAcl(WorkbenchController c, Invocation inv, Output out) throws IOException {
        if (inv.args().isEmpty()) {
            java.util.Map<String, String> all = lanAccessLists();
            out.println(all.isEmpty() ? "no access lists: every shared document is visible to every FULL_PEER"
                    : "documents with access lists:");
            all.forEach((sha, entries) -> out.printf("  %s  %s", sha.substring(0, 12), entries));
            return;
        }
        String sha = resolveContent(c, inv.joinedArgs());
        String entries;
        if (inv.has("grant")) {
            entries = lanAcl(sha, inv.option("grant", ""), true);
        } else if (inv.has("revoke")) {
            entries = lanAcl(sha, inv.option("revoke", ""), false);
        } else {
            LanSyncService s = zeroTrustLan();
            List<String> raw = s.store().acl().entries(sha);
            entries = raw.isEmpty() ? "" : describe(requireZeroTrust(), raw);
        }
        out.println(sha.substring(0, 12) + ": " + (entries.isEmpty()
                ? "no access list - visible to every FULL_PEER, to no guest" : entries));
    }

    /** A document or binary asset of the project by hash prefix or name. */
    public static String resolveContent(WorkbenchController c, String token) {
        return c.workbench().resolve(token).map(org.example.model.DocumentRecord::sha256)
                .or(() -> c.resolveAsset(token).map(org.example.model.BinaryAsset::sha256))
                .orElseThrow(() -> new IllegalArgumentException("no unique document or asset matches '" + token + "'"));
    }

    /** A trusted device, or {@code dept:NAME}, as an access-list entry (guests always get one-shot grants). */
    private static String aclEntry(ZeroTrust z, String token) {
        String t = token.strip();
        if (t.regionMatches(true, 0, "dept:", 0, 5) || t.regionMatches(true, 0, "department=", 0, 11)) {
            return AccessList.entry(t);
        }
        return ZeroTrust.grantEntry(device(z.trust(), t));
    }

    private static TrustStore.Device device(TrustStore trust, String token) {
        return trust.resolve(token).orElseThrow(() ->
                new IllegalArgumentException("no single trusted device matches '" + token + "' (see lan-devices)"));
    }

    private static String describe(ZeroTrust z, List<String> entries) {
        List<String> out = new ArrayList<>();
        for (String e : entries) {
            boolean once = e.startsWith("once:");
            String fp = once ? e.substring(5) : e;
            out.add(e.startsWith("dept:") ? e : z.trust().find(fp).map(TrustStore.Device::name)
                    .orElse(DeviceIdentity.display(fp)) + (once ? " (one-shot)" : ""));
        }
        return String.join(", ", out);
    }

    private void cmdPreview(WorkbenchController c, Invocation inv, Output out) {
        if (inv.has("close")) {
            preview.dismiss();
            out.println("preview closed");
            return;
        }
        if (inv.has("limits")) {
            PreviewService.Limits l = preview.limits();
            l.maxBytes().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> out.printf("  %-5s %s", e.getKey().extension(), human(e.getValue())));
            out.printf("  budget: %d chars, %d lines, %d PDF pages", l.maxChars(), l.maxLines(), l.maxPdfPages());
            return;
        }
        if (inv.args().isEmpty()) {
            throw new IllegalArgumentException("usage: preview <row|path|id> [--lines N] | --close | --limits");
        }
        Path path = c.resolveTarget(inv.joinedArgs())
                .orElseThrow(() -> new IllegalArgumentException("no result row, file or document id: " + inv.joinedArgs()));
        PreviewResult result = preview.preview(path);
        out.println(result.describe());
        if (result instanceof PreviewResult.Ready(Path p, PreviewDocument doc)) {
            for (PreviewLine line : doc.lines(0, inv.intOption("lines", PREVIEW_LINES, 1, 500))) {
                out.println(render(line));
            }
        }
    }

    /** Terminal rendering of one structured line. */
    public static String render(PreviewLine line) {
        return switch (line) {
            case PreviewDocument.Text t -> t.text();
            case PreviewDocument.TableRow r -> "| " + String.join(" | ", r.cells()) + " |" + (r.header() ? "  <header>" : "");
            case PreviewDocument.Section s -> "── " + s.label() + " ──";
            case PreviewDocument.Notice n -> "! " + n.text();
        };
    }

    private void cmdFocus(Invocation inv, Output out) {
        FocusModeCoordinator f = focus();
        if (inv.has("on")) {
            f.enter();
        } else if (inv.has("off")) {
            f.exit();
        } else if (inv.has("toggle") || inv.options().isEmpty()) {
            f.toggle();
        }
        if (inv.options().containsKey("quiet")) {
            String v = inv.option("quiet", "").toLowerCase(Locale.ROOT);
            f.setQuiet(switch (v) {
                case "on", "true", "1" -> true;
                case "off", "false", "0" -> false;
                default -> throw new IllegalArgumentException("--quiet expects on|off");
            });
        }
        if (inv.options().containsKey("notifications")) {
            // Persisted per project by the view, which owns the settings file.
            f.setNotificationPolicy(FocusModeCoordinator.NotificationPolicy
                    .parse(inv.option("notifications", ""))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "--notifications expects user_actions_only|all")));
        }
        FocusModeCoordinator.FocusState s = f.state();
        out.printf("focus: %s, quiet: %s, notifications: %s, hidden: %s", s.focus() ? "on" : "off",
                s.quiet() ? "on" : "off", f.notificationPolicy().id(),
                s.hidden().isEmpty() ? "nothing" : s.hidden());
        if (!s.quiet()) {
            long suppressed = f.drainSuppressedNotifications();
            if (suppressed > 0) {
                out.println(suppressed + " notification(s) were suppressed while quiet");
            }
        }
    }

    private void cmdWeb(WorkbenchController c, Invocation inv, Output out) throws AnswerException {
        String question = inv.joinedArgs();
        if (question.isBlank()) {
            throw new IllegalArgumentException("usage: @web <question>");
        }
        LineStreamer streamer = new LineStreamer(out);
        QueryOutcome outcome = submitQuery(WebSearchBridge.PREFIX + " " + question, streamer);
        streamer.flush();
        if (!(outcome instanceof QueryOutcome.Web(HybridOutcome hybrid))) {
            return;
        }
        switch (hybrid) {
            case HybridOutcome.Answered a -> {
                out.printf("sources: %d (web kept %d of %d snippets, %.0f%% of fetched text dropped); tokens %d in / %d out",
                        a.sources().size(), a.distillation().kept(), a.distillation().fetched(),
                        a.distillation().reduction() * 100, a.stats().promptTokens(), a.stats().outputTokens());
                for (WebSearchBridge.Source s : a.sources()) {
                    out.printf("  [%s] %s — %s", s.tag(), s.title(), s.location());
                }
            }
            case HybridOutcome.SnippetsOnly s -> {
                out.printf("no AI model configured; %d relevant web snippet(s) of %d:", s.snippets().size(),
                        s.distillation().fetched());
                for (WebSearchBridge.WebSnippet w : s.snippets()) {
                    out.printf("  %d. %s — %s", w.rank(), w.title(), w.url());
                    out.println("     " + w.text());
                }
            }
            case HybridOutcome.LocalOnly l -> {
                out.println("web/AI not used (" + l.reason() + (l.detail().isBlank() ? "" : ": " + l.detail())
                        + ") — local results:");
                ViewMode mode = c.view().mode();
                out.printf("%d hit(s) [%s]", c.view().size(), mode);
                for (int row = 0; row < c.view().size(); row++) {
                    StringBuilder sb = out.scratch();
                    c.view().render(row, sb);
                    out.println(sb);
                }
            }
        }
    }

    /** A clear reason instead of "set GEMINI_API_KEY" when IT turned AI answers off. */
    private void requireAiAllowed() throws AnswerException {
        if (!services.policy().aiAllowed()) {
            throw new AnswerException(AnswerException.Kind.NOT_CONFIGURED,
                    "AI answers are turned off by your organization's IT policy; local search is available");
        }
    }

    private void cmdAsk(WorkbenchController c, Invocation inv, Output out) throws AnswerException {
        String question = inv.joinedArgs();
        if (question.isBlank()) {
            throw new IllegalArgumentException("usage: ask [--dry-run] [--only <file|folder|id>] <question>");
        }
        java.util.function.Predicate<String> scope = inv.options().containsKey("only")
                ? ResearchCommands.documentsFor(c, inv.option("only", "")) : null;
        if (inv.flags().contains("dry-run")) {
            if (!services.policy().aiAllowed()) {
                out.println("(AI answers are turned off by your organization's IT policy; this shows what would be"
                        + " sent if they were allowed)");
            }
            Workbench.AskPlan plan = c.workbench().plan(question, scope);
            switch (plan.outcome()) {
                case NO_MATCH -> out.println("no indexed passage matches: the model would not be called");
                case BELOW_THRESHOLD -> out.printf("best evidence scores %.2f, below the %.2f threshold: the model"
                        + " would not be called", plan.topScore(), Workbench.DEFAULT_MIN_SCORE);
                case ANSWERED -> {
                    out.printf("would send %d passage(s) to %s:", plan.sources().size(),
                            plan.model() != null ? plan.model() : services.policy().aiAllowed()
                                    ? "the model (none configured: set GEMINI_API_KEY)" : "the model");
                    for (Workbench.Source s : plan.sources()) {
                        String where = c.document(s.docId()).map(d -> ResearchCommands.location(d.type(), s.page()))
                                .orElse("");
                        out.printf("  [%s] %s%s · passage #%d · %,d chars · score %.2f", s.tag(), s.fileName(),
                                where.isEmpty() ? "" : " · " + where, s.chunkIndex(), s.chars(), s.score());
                    }
                    QuotaGate.Snapshot q = c.workbench().quota().snapshot();
                    out.printf("prompt about %,d characters ≈ %,d tokens (+%d earlier turn(s) of context); AI quota"
                                    + " left: %s", plan.promptChars(), plan.estimatedPromptTokens(), plan.historyTurns(),
                            q.limit() == Integer.MAX_VALUE ? "unlimited" : q.remaining() + "/" + q.limit());
                }
            }
            out.println("(dry run: nothing was sent and no quota was used)");
            return;
        }
        requireAiAllowed();
        LineStreamer streamer = new LineStreamer(out);
        Workbench.AskOutcome outcome = c.workbench().ask(question, streamer, scope);
        streamer.flush();
        switch (outcome.outcome()) {
            case NO_MATCH -> out.println("no indexed passage matches this question; the model was not called");
            case BELOW_THRESHOLD -> out.printf("best evidence scores %.2f, below the %.2f threshold; the model was"
                    + " not called", outcome.topScore(), Workbench.DEFAULT_MIN_SCORE);
            case ANSWERED -> {
                for (Workbench.Source s : outcome.sources()) {
                    out.printf("  [%s] %s%s · passage #%d", s.tag(), s.fileName(),
                            s.page() > 0 ? " · p." + s.page() : "", s.chunkIndex());
                }
                out.printf("%s · tokens %d in / %d out", outcome.stats().model(), outcome.stats().promptTokens(),
                        outcome.stats().outputTokens());
                admin.audit().record(Level.INFO, Category.AI_CALL, "model", outcome.stats().model(), "promptTokens",
                        String.valueOf(outcome.stats().promptTokens()), "outputTokens",
                        String.valueOf(outcome.stats().outputTokens()));
            }
        }
    }

    private void cmdWatch(WorkbenchController c, Invocation inv, Output out) {
        java.util.Map<Path, List<Path>> byFolder = new java.util.TreeMap<>();
        for (Path file : projects.watchedPaths()) {
            byFolder.computeIfAbsent(file.getParent(), k -> new ArrayList<>()).add(file);
        }
        java.time.format.DateTimeFormatter stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (java.util.Map.Entry<Path, List<Path>> e : byFolder.entrySet()) {
            out.println(e.getKey() + "  (" + e.getValue().size() + " file(s))");
            for (Path file : e.getValue()) {
                String changed;
                try {
                    changed = stamp.format(java.time.LocalDateTime.ofInstant(
                            java.nio.file.Files.getLastModifiedTime(file).toInstant(), java.time.ZoneId.systemDefault()));
                } catch (IOException | RuntimeException ex) {
                    changed = "not reachable";
                }
                out.printf("    %s  %s", changed, file.getFileName());
            }
        }
        out.println(projects.watchedPaths().size() + " watched file(s) in " + byFolder.size() + " folder(s)"
                + "; changes are re-indexed automatically");
    }

    private void cmdProject(Invocation inv, Output out) throws IOException {
        Optional<ActiveProject> current = projects.active();
        if (inv.options().containsKey("new")) {
            Path dir = inv.options().containsKey("dir") ? Path.of(inv.option("dir", "")) : null;
            ProjectProfile p = projects.create(inv.option("new", ""), dir);
            admin.audit().record(Level.INFO, Category.PROJECT, "op", "create", "project", p.name(), "root",
                    p.root().toString());
            out.println("created project '" + p.name() + "' in " + p.root() + "  (project-switch " + p.name() + ")");
        } else if (inv.has("save")) {
            Optional<org.example.storage.IndexStorageEngine.SaveReport> r = projects.saveActive();
            out.println(r.map(s -> "saved " + s.documents() + " document(s) and manifest").orElse("manifest written; index unchanged"));
        } else if (inv.has("info")) {
            ActiveProject a = current.orElseThrow();
            out.println("project: " + a.profile().name() + "  (" + a.profile().id() + ")");
            out.println("  root:     " + a.profile().root());
            out.println("  index:    " + a.profile().indexFile());
            out.println("  keys:     " + a.profile().keyBindingsFile());
            out.println("  manifest: " + a.profile().manifestFile());
            out.printf("  documents: %d, watched sources: %d, active since %s", a.controller().workbench().documents().size(),
                    projects.watchedFiles(), a.activatedAt());
        } else {
            String activeId = current.map(a -> a.profile().id()).orElse("");
            for (ProjectProfile p : projects.list()) {
                out.printf("  %s %-24s %s", p.id().equals(activeId) ? "*" : " ", p.name(), p.root());
            }
        }
    }

    private void cmdSwitch(Invocation inv, Output out) {
        if (inv.args().isEmpty()) {
            throw new IllegalArgumentException("usage: project-switch <name|id|path>");
        }
        ProjectProfile target = projects.find(inv.joinedArgs())
                .orElseThrow(() -> new IllegalArgumentException("no project '" + inv.joinedArgs() + "' (project --list)"));
        out.println("switching to '" + target.name() + "'… (output continues in that project's terminal)");
        // Runs on its own thread: this terminal belongs to the project being unloaded and is closed mid-switch.
        CompletableFuture<SwitchReport> future = projects.switchAsync(target);
        try {
            // join(), not get(): unloading this project shuts its terminal down and interrupts this very thread; the
            // switch must still be awaited so a failure is audited instead of being reported as "interrupted".
            future.join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            admin.audit().record(Level.ERROR, Category.PROJECT, "op", "switch", "project", target.name(), "msg",
                    String.valueOf(cause.getMessage()));
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    private static String human(long bytes) {
        return bytes >= (1 << 20) ? (bytes >> 20) + " MB" : (bytes >> 10) + " KB";
    }

    /** Turns streamed answer deltas into complete terminal lines, never one unbounded string. */
    private static final class LineStreamer implements TokenSink {
        private static final int MAX_PENDING = 400;
        private final Output out;
        private final StringBuilder pending = new StringBuilder(256);

        LineStreamer(Output out) {
            this.out = out;
        }

        @Override
        public void accept(String delta) {
            for (int i = 0; i < delta.length(); i++) {
                char ch = delta.charAt(i);
                if (ch == '\n' || pending.length() >= MAX_PENDING) {
                    out.println(pending);
                    pending.setLength(0);
                    if (ch == '\n') {
                        continue;
                    }
                }
                pending.append(ch);
            }
        }

        void flush() {
            if (!pending.isEmpty()) {
                out.println(pending);
                pending.setLength(0);
            }
        }
    }

    // ================================================================== shutdown

    @Override
    public void close() {
        stopLan();
        HealthReporter r = reporter;
        if (r != null) {
            r.close();
        }
        projects.close();
        preview.close();
        web.close();
        admin.close();
    }
}
