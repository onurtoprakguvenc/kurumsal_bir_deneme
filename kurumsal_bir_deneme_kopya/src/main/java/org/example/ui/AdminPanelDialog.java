package org.example.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import org.example.admin.AdminControlEngine;
import org.example.admin.CentralPolicy;
import org.example.core.QuotaGate;
import org.example.repl.InternalTerminalEngine;
import org.example.util.AppInfo;
import org.example.workbench.Diagnostics;
import org.example.workbench.ExtendedWorkbenchController;
import org.example.workbench.HealthReporter;
import org.example.workbench.WorkbenchController;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The IT administration page: one window with the installation's state, the enforced policy, health, audit log and
 * support tools.
 *
 * <p>It reads state directly, but every <em>change</em> is sent as the matching terminal command
 * ({@code admin unlock}, {@code blacklist}, {@code limit}, {@code audit --export}, {@code gc}), so access control,
 * central-policy locks, audit entries and secret redaction are enforced in exactly one place, the admin engine.
 * Settings fixed by the central IT policy are shown with a lock and cannot be edited here.</p>
 */
final class AdminPanelDialog extends Dialog<Void> {

    private static final String LOCK = "🔒 ";

    private final ExtendedWorkbenchController ext;
    private final WorkbenchController controller;
    private final Function<String, CompletableFuture<InternalTerminalEngine.Outcome>> command;
    private final Consumer<Path> reveal;
    private final List<Runnable> refreshers = new ArrayList<>();
    private final TabPane tabs;
    private final Tab devicesTab;

    /**
     * @param command runs a terminal command in the active project (its outcome completes the future)
     * @param reveal  shows a file in the system file manager (audited like every file action)
     */
    AdminPanelDialog(ExtendedWorkbenchController ext, WorkbenchController controller,
                     Function<String, CompletableFuture<InternalTerminalEngine.Outcome>> command, Consumer<Path> reveal) {
        this.ext = ext;
        this.controller = controller;
        this.command = command;
        this.reveal = reveal;
        initModality(Modality.NONE);
        setTitle("Yönetim Paneli");
        setHeaderText("BT yönetimi · " + AppInfo.host() + " · sürüm " + AppInfo.version());
        setResizable(true);

        TrustedDevicesPane devices = new TrustedDevicesPane(ext, controller, this::child);
        refreshers.add(devices::refresh);
        addEventHandler(javafx.scene.control.DialogEvent.DIALOG_HIDDEN, e -> devices.dispose());
        devicesTab = tab("Cihazlar", devices);
        tabs = new TabPane(
                tab("Genel", overview()),
                tab("Politika", policy()),
                devicesTab,
                tab("Sağlık", health()),
                tab("Denetim Kaydı", audit()),
                tab("Destek", support()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefSize(720, 540);
        getDialogPane().setContent(tabs);
        getDialogPane().getButtonTypes().setAll(new ButtonType("Kapat", ButtonBar.ButtonData.CANCEL_CLOSE));
        refreshAll();
    }

    /** Opens on the zero-trust device management page (the status bar's security badge). */
    void showDevices() {
        tabs.getSelectionModel().select(devicesTab);
    }

    /** A dialog opened from this window: same owner chain and stylesheet. */
    private Dialog<?> child(Dialog<?> dialog) {
        if (getDialogPane().getScene() != null && getDialogPane().getScene().getWindow() != null) {
            dialog.initOwner(getDialogPane().getScene().getWindow());
        }
        dialog.getDialogPane().getStylesheets().setAll(getDialogPane().getStylesheets());
        dialog.getDialogPane().getStyleClass().add("dwb-dialog");
        return dialog;
    }

    private static Tab tab(String title, Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("admin-scroll");
        return new Tab(title, scroll);
    }

    private void refreshAll() {
        refreshers.forEach(Runnable::run);
    }

    /** Runs a command, then refreshes every tab (on the FX thread). */
    private void run(String line) {
        command.apply(line).whenComplete((outcome, error) -> Fx.run(this::refreshAll));
    }

    // ================================================================== overview

    private Node overview() {
        AdminControlEngine admin = ext.admin();
        Label machine = info();
        Label policyState = info();
        Label access = info();
        Label ai = info();
        Label memoryText = info();
        ProgressBar memory = new ProgressBar(0);
        memory.setMaxWidth(Double.MAX_VALUE);
        Label project = info();

        PasswordField secret = new PasswordField();
        secret.setPromptText("Yönetici parolası");
        HBox.setHgrow(secret, Priority.ALWAYS);
        Button unlock = button("Kilidi Aç", () -> {
            String pw = secret.getText();
            secret.clear();
            if (!pw.isBlank() && !pw.contains("\"")) {
                run("admin unlock \"" + pw + "\""); // the terminal records it as 'admin unlock ***'
            }
        });
        secret.setOnAction(e -> unlock.fire());
        Button lock = button("Kilitle", () -> run("admin lock"));
        Button gc = button("Bellek Temizliği (GC)", () -> run("gc"));

        refreshers.add(() -> {
            machine.setText("Bilgisayar: " + AppInfo.host() + " · kullanıcı " + AppInfo.user() + " · " + AppInfo.os()
                    + " · Java " + System.getProperty("java.version"));
            CentralPolicy p = admin.policy();
            policyState.setText(!p.present() ? "Merkezi politika: yok (yerel ayarlar geçerli)"
                    : "Merkezi politika: " + p.file() + p.version().map(v -> " · sürüm " + v).orElse("")
                    + (p.writableByUser() ? "  ⚠ bu kullanıcı dosyayı değiştirebiliyor — ayarlar zorunlu değil" : "")
                    + (p.problems().isEmpty() ? "" : "  ⚠ " + p.problems().size() + " sorun"));
            access.setText("Erişim denetimi: " + (admin.protectedMode() ? "açık" : "kapalı (parola yok)")
                    + (p.passphraseHash().isPresent() ? " · parola merkezi" : "")
                    + " · oturum: " + (admin.unlocked() ? "açık" : "kilitli"));
            ai.setText("Yapay zekâ: " + (!p.aiAllowed() ? "politika ile KAPALI"
                    : ext.aiConfigured() ? "yapılandırılmış" : "anahtar yok (GEMINI_API_KEY)")
                    + " · web araması: " + (!p.webAllowed() ? "politika ile KAPALI"
                    : ext.web().config().configured() ? "yapılandırılmış" : "kapalı"));
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            double ratio = (double) used / Math.max(1, rt.maxMemory());
            memory.setProgress(ratio);
            memoryText.setText(String.format(Locale.ROOT, "Bellek: %d / %d MB (%%%d) · tavan %%%.0f · %d işçi",
                    used >> 20, rt.maxMemory() >> 20, Math.round(ratio * 100), admin.settings().memoryCeiling() * 100,
                    admin.settings().workers()));
            var stats = controller.workbench().stats();
            QuotaGate.Snapshot q = controller.workbench().quota().snapshot();
            project.setText("Proje: " + ext.projects().active().map(a -> a.profile().name()).orElse("-") + " · "
                    + stats.documents() + " belge · " + stats.chunks() + " parça · AI kotası "
                    + (q.limit() == Integer.MAX_VALUE ? "sınırsız" : q.remaining() + "/" + q.limit() + " kaldı"));
            lock.setDisable(!admin.protectedMode());
            unlock.setDisable(!admin.protectedMode());
            secret.setDisable(!admin.protectedMode());
        });

        return column(machine, policyState, access, ai, project,
                section("BELLEK"), memoryText, memory,
                section("YÖNETİCİ OTURUMU"), new HBox(6, secret, unlock, lock),
                note("Parola yalnızca bu oturumu açar; terminal geçmişine ve denetim kaydına '***' olarak yazılır."),
                section("İŞLEMLER"), new HBox(6, gc, button("Yenile", this::refreshAll)));
    }

    // ================================================================== policy

    private Node policy() {
        AdminControlEngine admin = ext.admin();
        GridPane table = new GridPane();
        table.setHgap(16);
        table.setVgap(6);
        ListView<String> exclusions = new ListView<>();
        exclusions.getStyleClass().add("admin-list");
        exclusions.setPrefHeight(170);
        exclusions.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : (admin.centralExclusion(item) ? LOCK : "") + item);
            }
        });
        TextField glob = new TextField();
        glob.setPromptText("ör. *.log veya **/arsiv/**");
        HBox.setHgrow(glob, Priority.ALWAYS);
        Button add = button("Ekle", () -> {
            String g = glob.getText().strip();
            if (!g.isEmpty() && !g.contains("\"")) {
                run("blacklist --add \"" + g + "\"");
                glob.clear();
            }
        });
        Button remove = button("Seçileni Kaldır", () -> {
            String g = exclusions.getSelectionModel().getSelectedItem();
            if (g != null && !g.contains("\"")) {
                run("blacklist --remove \"" + g + "\"");
            }
        });
        exclusions.getSelectionModel().selectedItemProperty().addListener((obs, old, item) ->
                remove.setDisable(item == null || admin.centralExclusion(item)));

        Slider ceiling = new Slider(0.50, 0.95, admin.settings().memoryCeiling());
        ceiling.setMajorTickUnit(0.05);
        ceiling.setMinorTickCount(0);
        ceiling.setSnapToTicks(true);
        HBox.setHgrow(ceiling, Priority.ALWAYS);
        Label ceilingValue = new Label();
        ceiling.valueProperty().addListener((obs, old, v) -> ceilingValue.setText(
                String.format(Locale.ROOT, "%%%.0f", v.doubleValue() * 100)));
        Button applyCeiling = button("Uygula", () -> run(String.format(Locale.ROOT, "limit --memory %.2f",
                ceiling.getValue())));
        Spinner<Integer> workers = new Spinner<>(1, 8, admin.settings().workers());
        Button applyWorkers = button("Uygula", () -> run("limit --workers " + workers.getValue()));
        Label problems = info();
        problems.getStyleClass().add("admin-warning");

        refreshers.add(() -> {
            CentralPolicy p = admin.policy();
            AdminControlEngine.Settings s = admin.settings();
            table.getChildren().clear();
            int r = 0;
            row(table, r++, "Politika dosyası", p.present() ? p.file().toString() : "yok (" + p.file() + ")", false);
            row(table, r++, "Hariç tutmalar", s.exclusions().size() + " desen" + (p.exclusions().isEmpty() ? ""
                    : " (" + p.exclusions().size() + " merkezi, " + (p.replacesExclusions() ? "yalnız merkezi" : "yerele ek")
                    + ")"), !p.exclusions().isEmpty());
            row(table, r++, "Bellek tavanı", String.format(Locale.ROOT, "%%%.0f", s.memoryCeiling() * 100),
                    p.memoryCeiling().isPresent());
            row(table, r++, "Paralel işçi", String.valueOf(s.workers()), p.workers().isPresent());
            row(table, r++, "Yönetici oturumu", s.unlockMinutes() + " dk", p.unlockMinutes().isPresent());
            row(table, r++, "Yönetici parolası", p.passphraseHash().isPresent() ? "merkezi"
                    : s.passHash() != null ? "yerel" : "yok", p.passphraseHash().isPresent());
            row(table, r++, "Yapay zekâ", p.aiAllowed() ? "izinli" : "KAPALI", p.aiLocked());
            QuotaGate.Snapshot q = controller.workbench().quota().snapshot();
            row(table, r++, "AI kotası", q.limit() == Integer.MAX_VALUE ? "sınırsız" : "günde " + q.limit()
                    + " çağrı · bugün " + q.used() + " kullanıldı" + (q.open() ? "" : " · şu an izinli saat dışı"),
                    p.overrides("DWB_AI_QUOTA") || p.overrides("DWB_AI_RATE")
                    || p.overrides("DWB_AI_HOURS") || p.overrides("DWB_AI_DAYS"));
            row(table, r++, "Web araması", !p.webAllowed() ? "KAPALI" : ext.web().config().configured()
                    ? String.valueOf(ext.web().config().endpoint()) : "yapılandırılmamış", p.webLocked());
            row(table, r++, "Ağ paylaşımı", !p.lanAllowed() ? "KAPALI"
                    : p.lanRequiresSecret() ? "yalnız kimliği doğrulanmış eşlerle" : "izinli", !p.lanAllowed() || p.lanRequiresSecret());
            ExtendedWorkbenchController.LanShield shield = ext.lanShield();
            row(table, r++, "Güven modu", switch (shield.kind()) {
                case ZERO_TRUST -> "sıfır güven · v3 şifreli tünel (AES-256-GCM)";
                case LEGACY_SECRET -> "legacy · DWB_SECRET (şifresiz)";
                case LEGACY_OPEN -> "legacy · açık mod (şifresiz)";
                case STARTING -> "başlatılıyor…";
                case OFF -> "kapalı · " + shield.reason();
            }, p.overrides("DWB_TRUST"));
            row(table, r++, "Sağlık raporu", p.reportFolder().map(f -> f + " · " + p.reportIntervalMinutes() + " dk")
                    .orElse("kapalı"), p.reportFolder().isPresent());
            row(table, r, "Destek klasörü", p.supportFolder().map(Path::toString).orElse("masaüstü"),
                    p.supportFolder().isPresent());

            exclusions.getItems().setAll(admin.exclusions());
            ceiling.setValue(s.memoryCeiling());
            ceilingValue.setText(String.format(Locale.ROOT, "%%%.0f", s.memoryCeiling() * 100));
            boolean ceilingLocked = p.memoryCeiling().isPresent();
            ceiling.setDisable(ceilingLocked);
            applyCeiling.setDisable(ceilingLocked);
            workers.getValueFactory().setValue(s.workers());
            workers.setDisable(p.workers().isPresent());
            applyWorkers.setDisable(p.workers().isPresent());
            remove.setDisable(true);
            List<String> issues = new ArrayList<>(p.problems());
            if (p.writableByUser()) {
                issues.add("Politika dosyasını kullanıcılar değiştirebiliyor: Users grubuna yalnız okuma izni verin"
                        + " (deploy\\Install-DocumentWorkbench.ps1 bunu yapar).");
            }
            problems.setText(String.join("\n", issues));
            problems.setVisible(!issues.isEmpty());
            problems.setManaged(!issues.isEmpty());
        });

        return column(problems, table,
                section("HARİÇ TUTULANLAR  (" + LOCK.strip() + " = merkezi politika, burada kaldırılamaz)"),
                exclusions, new HBox(6, glob, add, remove),
                section("BELLEK TAVANI"), new HBox(8, ceiling, ceilingValue, applyCeiling),
                section("TOPLU EKLEMEDE PARALEL İŞÇİ"), new HBox(8, workers, applyWorkers),
                note(LOCK + "işaretli ayarlar merkezi BT politikasıyla belirlenmiştir. Değişiklikler yönetici oturumu"
                        + " gerektirebilir ve denetim kaydına yazılır."));
    }

    private static void row(GridPane grid, int r, String name, String value, boolean central) {
        Label n = new Label(name);
        n.getStyleClass().add("admin-key");
        Label v = new Label((central ? LOCK : "") + value);
        v.setWrapText(true);
        if (central) {
            v.setTooltip(new Tooltip("Merkezi BT politikasıyla belirlenmiştir"));
        }
        grid.addRow(r, n, v);
    }

    // ================================================================== health

    private Node health() {
        ListView<String> findings = new ListView<>();
        findings.getStyleClass().add("admin-list");
        findings.setPrefHeight(220);
        Label metrics = info();
        Label snapshot = info();
        Runnable load = () -> Thread.ofVirtual().name("dwb-admin-health").start(() -> {
          try {
            AdminControlEngine.HealthReport report = ext.admin().healthAudit(controller);
            List<String> lines = report.findings().stream().map(f -> icon(f.severity()) + "  " + f.message()
                    + "   [" + f.code() + "]").toList();
            StringBuilder m = new StringBuilder();
            new java.util.TreeMap<>(report.metrics()).forEach((k, v) -> m.append(k).append(" = ").append(v).append("   "));
            StringBuilder snap = new StringBuilder();
            try {
                controller.storage().verify().forEach(check -> snap.append(check.origin()).append(": ")
                        .append(!check.present() ? "yok" : check.valid() ? "sağlam · " + check.documents() + " belge"
                                : "BOZUK · " + check.problem()).append("   "));
            } catch (java.io.IOException e) {
                snap.append("okunamadı: ").append(e.getMessage());
            }
            Fx.run(() -> {
                findings.getItems().setAll(lines);
                metrics.setText(m.toString());
                snapshot.setText(snap.toString());
            });
          } catch (RuntimeException e) {
            Fx.run(() -> findings.getItems().setAll("⛔  Sağlık denetimi yapılamadı: " + e));
          }
        });
        refreshers.add(load);
        return column(section("BULGULAR"), findings, section("ÖLÇÜMLER"), metrics,
                section("İNDEKS DOSYASI"), snapshot, new HBox(6, button("Yeniden Denetle", load),
                        button("Değişen Kaynakları Göster", () -> run("stale")),
                        button("Taranmış PDF'ler", () -> run("ocr-needed"))),
                note("'Değişen kaynaklar' ve 'taranmış PDF'ler' sonuçları terminalde listelenir."));
    }

    private static String icon(AdminControlEngine.Severity s) {
        return switch (s) {
            case OK -> "✔";
            case INFO -> "ℹ";
            case WARN -> "⚠";
            case CRITICAL -> "⛔";
        };
    }

    // ================================================================== audit

    private Node audit() {
        AdminControlEngine admin = ext.admin();
        ListView<String> lines = new ListView<>();
        lines.getStyleClass().add("admin-list");
        lines.setPrefHeight(300);
        TextField filter = new TextField();
        filter.setPromptText("Süz: ör. FILE_ACCESS, AUTH, dosya adı…");
        HBox.setHgrow(filter, Priority.ALWAYS);
        List<String> all = new ArrayList<>();
        Runnable applyFilter = () -> {
            String f = filter.getText().strip().toLowerCase(Locale.ROOT);
            lines.getItems().setAll(f.isEmpty() ? all
                    : all.stream().filter(l -> l.toLowerCase(Locale.ROOT).contains(f)).toList());
        };
        filter.textProperty().addListener((obs, old, v) -> applyFilter.run());
        Label locked = info();
        Runnable load = () -> {
            boolean allowed = admin.unlocked();
            locked.setText(allowed ? "" : "Denetim kaydını görmek için Genel sekmesinden yönetici oturumunu açın.");
            locked.setVisible(!allowed);
            locked.setManaged(!allowed);
            if (!allowed) {
                all.clear();
                applyFilter.run();
                return;
            }
            Thread.ofVirtual().name("dwb-admin-audit").start(() -> {
                List<String> tail;
                try {
                    tail = admin.audit().tail(300);
                } catch (java.io.IOException | RuntimeException e) {
                    tail = List.of("okunamadı: " + e);
                }
                List<String> newestFirst = new ArrayList<>(tail);
                java.util.Collections.reverse(newestFirst);
                Fx.run(() -> {
                    all.clear();
                    all.addAll(newestFirst);
                    applyFilter.run();
                });
            });
        };
        refreshers.add(load);
        DatePicker since = new DatePicker(LocalDate.now().minusMonths(1));
        Button export = button("Dışa Aktar", () -> {
            LocalDate d = since.getValue();
            if (d != null) {
                run("audit --export --since " + d);
            }
        });
        return column(locked, new HBox(6, filter, button("Yenile", load)), lines,
                section("RAPOR İÇİN DIŞA AKTAR"), new HBox(8, new Label("Başlangıç:"), since, export),
                note("Dışa aktarılan dosya audit.log'un yanına yazılır; yolu terminalde görünür. Bu işlem de kayda"
                        + " geçer."));
    }

    // ================================================================== support

    private Node support() {
        Label result = info();
        Button reveal = button("Klasörde Göster", () -> { });
        reveal.setDisable(true);
        Path[] last = new Path[1];
        reveal.setOnAction(e -> {
            if (last[0] != null) {
                this.reveal.accept(last[0]);
            }
        });
        Button bundle = button("Destek Paketi Oluştur", () -> {
            result.setText("Hazırlanıyor…");
            Thread.ofVirtual().name("dwb-support-bundle").start(() -> {
                try {
                    Path zip = Diagnostics.writeSupportBundle(ext, Diagnostics.defaultBundleFolder(ext));
                    Fx.run(() -> {
                        last[0] = zip;
                        reveal.setDisable(false);
                        result.setText("Oluşturuldu: " + zip);
                    });
                } catch (java.io.IOException | RuntimeException e) {
                    Fx.run(() -> result.setText("Oluşturulamadı: " + e.getMessage()));
                }
            });
        });

        Label reportState = info();
        Button reportNow = button("Raporu Şimdi Yaz", () -> ext.healthReporter().ifPresent(r ->
                Thread.ofVirtual().name("dwb-health-report-now").start(() -> {
                    String text;
                    try {
                        text = "Yazıldı: " + r.reportNow();
                    } catch (java.io.IOException | RuntimeException e) {
                        text = "Yazılamadı: " + e.getMessage();
                    }
                    String shown = text;
                    Fx.run(() -> reportState.setText(shown));
                })));
        refreshers.add(() -> {
            Optional<HealthReporter> r = ext.healthReporter();
            reportNow.setDisable(r.isEmpty());
            if (r.isEmpty()) {
                reportState.setText("Paylaşılan klasöre sağlık raporu kapalı (merkezi politikada report.folder).");
            } else if (reportState.getText() == null || reportState.getText().isEmpty()
                    || reportState.getText().startsWith("Klasör")) {
                reportState.setText("Klasör: " + r.get().folder() + " · her " + ext.policy().reportIntervalMinutes()
                        + " dk" + (r.get().lastWritten() == null ? "" : " · son: " + r.get().lastWritten())
                        + (r.get().lastProblem() == null ? "" : " · sorun: " + r.get().lastProblem()));
            }
        });

        return column(section("DESTEK PAKETİ"),
                note("BT'ye göndermek için tek bir zip: sürüm, bilgisayar, bellek, politika, indeks sağlığı ve son"
                        + " denetim kayıtları. Belge içeriği, arama metni veya dosya içeriği YOKTUR. Varsayılan konum:"
                        + " merkezi destek klasörü, yoksa masaüstü."),
                new HBox(6, bundle, reveal), result,
                section("PAYLAŞILAN KLASÖRE SAĞLIK RAPORU"),
                note("BT politikada bir klasör belirlerse bu bilgisayar düzenli aralıklarla küçük bir durum dosyası"
                        + " yazar (belge adı veya içeriği içermez)."),
                reportState, new HBox(6, reportNow));
    }

    // ================================================================== small helpers

    private static VBox column(Node... nodes) {
        VBox box = new VBox(10, nodes);
        box.setPadding(new Insets(12));
        box.setFillWidth(true);
        return box;
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    private static Label info() {
        Label l = new Label();
        l.getStyleClass().add("admin-status");
        l.setWrapText(true);
        return l;
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("shortcut-default");
        l.setWrapText(true);
        return l;
    }

    private static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("ghost-button");
        b.setOnAction(e -> action.run());
        b.setAlignment(Pos.CENTER);
        return b;
    }
}
