package org.example.ui;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.example.admin.AdminControlEngine;
import org.example.p2p.DeviceIdentity;
import org.example.p2p.DeviceRole;
import org.example.p2p.TrustStore;
import org.example.workbench.ExtendedWorkbenchController;
import org.example.workbench.ExtendedWorkbenchController.LanShield;
import org.example.workbench.ExtendedWorkbenchController.LanShieldKind;
import org.example.workbench.WorkbenchController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The "Cihazlar" page of the administration window: this device's identity, pairing ("Yeni Cihaz Eşleştir",
 * "Bir Cihaza Katıl"), the trusted devices with editable role and department and a one-click revoke, unpaired devices
 * seen on the network, and per-document access lists.
 *
 * <p>Every change calls {@link ExtendedWorkbenchController} (the same methods the {@code lan-pair},
 * {@code lan-devices} and {@code lan-acl} terminal commands use), which validates and audits it. Changes run off the
 * FX thread; the view refreshes when they finish.</p>
 */
final class TrustedDevicesPane extends VBox {

    private static final String PAIR_LABEL = "➕ Yeni Cihaz Eşleştir";
    private static final String REVOKE_LABEL = "Güvenden Çıkar";

    private static final StringConverter<DeviceRole> ROLE_NAMES = new StringConverter<>() {
        @Override
        public String toString(DeviceRole role) {
            return role == null ? "" : role == DeviceRole.FULL_PEER ? "FULL_PEER · tam eş" : "RESTRICTED_GUEST · misafir";
        }

        @Override
        public DeviceRole fromString(String text) {
            return DeviceRole.parse(text).orElse(null);
        }
    };

    private final ExtendedWorkbenchController ext;
    private final WorkbenchController controller;
    private final UnaryOperator<Dialog<?>> style;
    private final Label identity = label("admin-status");
    private final Label state = label("admin-status");
    private final Label message = label("admin-warning");
    private final TableView<TrustStore.Device> devices = new TableView<>();
    private final ListView<String> unpaired = new ListView<>();
    private final ListView<String> accessLists = new ListView<>();
    private final ComboBox<String> aclTarget = new ComboBox<>();
    private final VBox managed = new VBox(10);
    private final Label adminState = label("admin-status");
    private Button pairButton;

    /**
     * @param style applies the application's stylesheet and owner to a child dialog
     */
    TrustedDevicesPane(ExtendedWorkbenchController ext, WorkbenchController controller, UnaryOperator<Dialog<?>> style) {
        super(10);
        this.ext = ext;
        this.controller = controller;
        this.style = style;
        setPadding(new Insets(12));
        setFillWidth(true);

        ChoiceBox<DeviceRole> newRole = new ChoiceBox<>(FXCollections.observableArrayList(DeviceRole.values()));
        newRole.setConverter(ROLE_NAMES);
        newRole.setValue(DeviceRole.FULL_PEER);
        TextField newDept = new TextField();
        newDept.setPromptText("departman (ör. FINANCE)");
        newDept.setPrefColumnCount(12);
        Button pair = button(PAIR_LABEL, () -> withAdmin("yeni cihaz eşleştirmek", () -> {
            show(new PairingDialogs.PinDialog(ext, newRole.getValue(), newDept.getText()));
            refresh();
        }));
        pair.getStyleClass().add("accent-button");
        pair.setTooltip(new Tooltip("Tek tıkla 6 haneli, 5 dakika geçerli, tek denemelik PIN üretir"
                + " (yönetici oturumu gerekir)"));
        pairButton = pair;
        Button join = button("Bir Cihaza Katıl…", () -> {
            show(new PairingDialogs.JoinDialog(ext));
            refresh();
        });

        devices.setPlaceholder(new Label("Güvenilen cihaz yok: bu bilgisayar yalıtılmış durumda. “Yeni Cihaz Eşleştir”"
                + " ile başlayın."));
        devices.setPrefHeight(220);
        devices.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        devices.getColumns().setAll(List.of(nameColumn(), fingerprintColumn(), roleColumn(), departmentColumn(),
                onlineColumn(), revokeColumn()));

        unpaired.getStyleClass().add("admin-list");
        unpaired.setPrefHeight(90);
        unpaired.setPlaceholder(new Label("Son 10 dakikada eşleştirilmemiş cihaz görülmedi"));

        TextField document = new TextField();
        document.setPromptText("belge: hash öneki veya dosya adı");
        HBox.setHgrow(document, Priority.ALWAYS);
        aclTarget.setEditable(true);
        aclTarget.setPromptText("cihaz veya dept:AD");
        aclTarget.setPrefWidth(200);
        Button grant = button("İzin ver", () -> acl(document.getText(), true));
        Button revoke = button("İzni kaldır", () -> acl(document.getText(), false));
        accessLists.getStyleClass().add("admin-list");
        accessLists.setPrefHeight(110);
        accessLists.setPlaceholder(new Label("Erişim listesi yok: paylaşılan her belgeyi tüm FULL_PEER cihazlar görür"));

        managed.getChildren().setAll(
                adminState,
                new HBox(8, pair, newRole, newDept, join),
                note("PIN'i yetkili bilgisayarda üretin, yeni bilgisayarda “Bir Cihaza Katıl” ile girin; iki ekrandaki"
                        + " doğrulama kodu aynı olmalıdır."),
                section("GÜVENİLEN CİHAZLAR"), devices,
                note("Rol ve departman doğrudan tabloda değiştirilir (departman: yazıp Enter). “Güvenden Çıkar” cihazı"
                        + " anında yalıtır ve tüm erişim listelerinden siler. Eşleştirme ve güvenden çıkarma yönetici"
                        + " oturumu ister; kilit kapalıyken parola sorulur."),
                section("AĞDA GÖRÜLEN EŞLEŞTİRİLMEMİŞ CİHAZLAR"), unpaired,
                section("BELGE ERİŞİM LİSTELERİ"), new HBox(8, document, aclTarget, grant, revoke), accessLists,
                note("Listesi olan belgeyi yalnızca listedeki cihazlar/departmanlar görür ve çekebilir. Misafire verilen"
                        + " izin tek seferliktir."));
        getChildren().setAll(section("BU CİHAZ"), identity, state,
                new HBox(8, button("Yenile", this::refresh)), managed, message);
        refresh();
    }

    // ================================================================== refresh

    /** Re-reads the state (FX thread). */
    void refresh() {
        LanShield shield = ext.lanShield();
        boolean zeroTrust = ext.zeroTrust().isPresent();
        identity.setText(zeroTrust ? "Kimlik (Ed25519 parmak izi): " + DeviceIdentity.display(shield.fingerprint())
                + "   ·   " + shield.fingerprint() : "Sıfır güven kimliği yok");
        state.setText(switch (shield.kind()) {
            case ZERO_TRUST -> "🔒 Sıfır güven etkin · protokol v3 · aktarımlar AES-256-GCM ile şifreli · TCP "
                    + shield.transferPort() + " · " + shield.trusted() + " güvenilen cihaz";
            case LEGACY_SECRET -> "Legacy mod (DWB_SECRET): cihaz yönetimi yok, aktarımlar şifresiz";
            case LEGACY_OPEN -> "⚠ Legacy açık mod: ağdaki herkes erişebilir, aktarımlar şifresiz";
            case STARTING -> "LAN eşitleme başlatılıyor…";
            case OFF -> "LAN eşitleme kapalı: " + shield.reason();
        });
        managed.setDisable(!zeroTrust || shield.kind() != LanShieldKind.ZERO_TRUST);
        boolean unlocked = ext.lanAdminUnlocked();
        pairButton.setText(unlocked ? PAIR_LABEL : "🔒 " + PAIR_LABEL);
        adminState.setText(!ext.admin().protectedMode()
                ? "⚠ Yönetici parolası tanımlı değil: eşleştirme ve güvenden çıkarma bu bilgisayarı kullanan herkese"
                + " açık. Parolayı merkezi politikada (admin.passphrase.hash) ya da “admin passwd” ile tanımlayın."
                : unlocked ? "🔓 Yönetici oturumu açık: eşleştirme ve güvenden çıkarma kullanılabilir"
                : "🔒 Yönetici kilidi kapalı: “Yeni Cihaz Eşleştir” ve “Güvenden Çıkar” yönetici parolası ister");
        devices.getItems().setAll(ext.lanDevices());
        unpaired.getItems().setAll(ext.lanUnpaired().stream().map(s -> s.name() + "  ·  "
                + DeviceIdentity.display(s.fingerprint()) + "  ·  "
                + (s.transferPort() > 0 ? s.endpoint() : s.address().getHostAddress())).toList());
        Set<String> targets = new LinkedHashSet<>();
        for (TrustStore.Device d : ext.lanDevices()) {
            targets.add(d.name());
            if (!d.department().isEmpty()) {
                targets.add("dept:" + d.department());
            }
        }
        aclTarget.getItems().setAll(targets);
        if (shield.kind() == LanShieldKind.ZERO_TRUST) {
            try {
                accessLists.getItems().setAll(ext.lanAccessLists().entrySet().stream()
                        .map(e -> e.getKey().substring(0, 12) + "  " + controller.document(e.getKey())
                                .map(org.example.model.DocumentRecord::fileName).orElse("") + "  →  " + e.getValue())
                        .toList());
            } catch (RuntimeException e) {
                accessLists.getItems().clear();
            }
        } else {
            accessLists.getItems().clear();
        }
    }

    // ================================================================== actions

    /** Runs a change off the FX thread, then shows its outcome and refreshes. */
    private <T> void change(Callable<T> work, Consumer<T> done) {
        message.setText("");
        CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((result, error) -> Fx.run(() -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                message.setText("İşlem yapılamadı: " + cause.getMessage());
            } else if (done != null) {
                done.accept(result);
            }
            refresh();
        }));
    }

    private void acl(String documentToken, boolean grant) {
        String target = aclTarget.getEditor().getText() == null ? "" : aclTarget.getEditor().getText().strip();
        if (documentToken == null || documentToken.isBlank() || target.isEmpty()) {
            message.setText("Belge ve hedef (cihaz ya da dept:AD) gerekli.");
            return;
        }
        change(() -> {
            String sha = ExtendedWorkbenchController.resolveContent(controller, documentToken.strip());
            return sha.substring(0, 12) + ": " + ext.lanAcl(sha, target, grant);
        }, result -> message.setText((grant ? "İzin verildi · " : "İzin kaldırıldı · ") + result));
    }

    /**
     * Runs an admin-locked action: at once when the admin session is open (or no passphrase is configured), otherwise
     * after asking for the admin passphrase. The controller checks the session again, so this is convenience, not the
     * enforcement.
     */
    private void withAdmin(String purpose, Runnable action) {
        if (ext.lanAdminUnlocked()) {
            action.run();
            return;
        }
        Dialog<char[]> prompt = new Dialog<>();
        prompt.setTitle("Yönetici Kilidi");
        prompt.setHeaderText("🔒 " + Character.toUpperCase(purpose.charAt(0)) + purpose.substring(1)
                + " için yönetici oturumu gerekir");
        PasswordField secret = new PasswordField();
        secret.setPromptText("Yönetici parolası");
        Label hint = new Label("Oturum, politikadaki süre kadar açık kalır; giriş ve reddedilen denemeler denetim kaydına"
                + " yazılır.");
        hint.setWrapText(true);
        hint.getStyleClass().add("shortcut-default");
        VBox box = new VBox(10, secret, hint);
        box.setPadding(new Insets(12));
        box.setPrefWidth(420);
        prompt.getDialogPane().setContent(box);
        ButtonType unlock = new ButtonType("Kilidi Aç", ButtonBar.ButtonData.OK_DONE);
        prompt.getDialogPane().getButtonTypes().setAll(unlock, new ButtonType("Vazgeç", ButtonBar.ButtonData.CANCEL_CLOSE));
        prompt.setResultConverter(b -> b == unlock ? secret.getText().toCharArray() : null);
        prompt.setOnShown(e -> secret.requestFocus());
        style.apply(prompt);
        char[] pass = prompt.showAndWait().orElse(null);
        secret.clear();
        if (pass == null || pass.length == 0) {
            return;
        }
        AdminControlEngine.UnlockResult result = ext.admin().unlock(pass); // wipes the array
        switch (result) {
            case AdminControlEngine.UnlockResult.Unlocked u -> {
                message.setText("");
                refresh();
                action.run();
            }
            case AdminControlEngine.UnlockResult.NotRequired n -> action.run();
            case AdminControlEngine.UnlockResult.Denied d -> message.setText("Yönetici parolası yanlış; "
                    + d.remainingAttempts() + " deneme hakkı kaldı.");
            case AdminControlEngine.UnlockResult.LockedOut l -> message.setText("Çok fazla hatalı deneme: yönetici girişi "
                    + l.until() + " zamanına kadar kilitli.");
        }
        refresh();
    }

    private void show(Dialog<?> dialog) {
        style.apply(dialog).showAndWait();
    }

    // ================================================================== table columns

    private static TableColumn<TrustStore.Device, String> nameColumn() {
        TableColumn<TrustStore.Device, String> c = new TableColumn<>("Cihaz");
        c.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().name()));
        c.setPrefWidth(130);
        return c;
    }

    private static TableColumn<TrustStore.Device, String> fingerprintColumn() {
        TableColumn<TrustStore.Device, String> c = new TableColumn<>("Parmak izi");
        c.setCellValueFactory(d -> new ReadOnlyStringWrapper(DeviceIdentity.display(d.getValue().fingerprint())));
        c.setPrefWidth(190);
        return c;
    }

    private TableColumn<TrustStore.Device, String> onlineColumn() {
        TableColumn<TrustStore.Device, String> c = new TableColumn<>("Durum");
        c.setCellValueFactory(d -> new ReadOnlyStringWrapper(ext.lanOnline(d.getValue().fingerprint())
                ? "● çevrimiçi" : "○ görünmüyor"));
        c.setPrefWidth(90);
        return c;
    }

    private TableColumn<TrustStore.Device, TrustStore.Device> roleColumn() {
        TableColumn<TrustStore.Device, TrustStore.Device> c = new TableColumn<>("Rol");
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        c.setPrefWidth(190);
        c.setCellFactory(col -> new TableCell<>() {
            private final ChoiceBox<DeviceRole> box = new ChoiceBox<>(FXCollections.observableArrayList(DeviceRole.values()));

            {
                box.setConverter(ROLE_NAMES);
                box.setOnAction(e -> {
                    TrustStore.Device d = getItem();
                    if (d != null && box.getValue() != null && box.getValue() != d.role()) {
                        change(() -> ext.lanSetRole(d.fingerprint(), box.getValue()),
                                changed -> message.setText(changed.name() + ": " + changed.labels()));
                    }
                });
            }

            @Override
            protected void updateItem(TrustStore.Device d, boolean empty) {
                super.updateItem(d, empty);
                if (empty || d == null) {
                    setGraphic(null);
                    return;
                }
                box.setValue(d.role());
                setGraphic(box);
            }
        });
        return c;
    }

    private TableColumn<TrustStore.Device, TrustStore.Device> departmentColumn() {
        TableColumn<TrustStore.Device, TrustStore.Device> c = new TableColumn<>("Departman");
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        c.setPrefWidth(120);
        c.setCellFactory(col -> new TableCell<>() {
            private final TextField field = new TextField();

            {
                field.setPromptText("-");
                field.setOnAction(e -> commit());
                field.focusedProperty().addListener((obs, was, now) -> {
                    if (!now) {
                        commit();
                    }
                });
            }

            private void commit() {
                TrustStore.Device d = getItem();
                String value = field.getText() == null ? "" : field.getText().strip();
                if (d != null && !value.equalsIgnoreCase(d.department())) {
                    change(() -> ext.lanSetDepartment(d.fingerprint(), value),
                            changed -> message.setText(changed.name() + ": " + changed.labels()));
                }
            }

            @Override
            protected void updateItem(TrustStore.Device d, boolean empty) {
                super.updateItem(d, empty);
                if (empty || d == null) {
                    setGraphic(null);
                    return;
                }
                field.setText(d.department());
                setGraphic(field);
            }
        });
        return c;
    }

    private TableColumn<TrustStore.Device, TrustStore.Device> revokeColumn() {
        TableColumn<TrustStore.Device, TrustStore.Device> c = new TableColumn<>("");
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        c.setPrefWidth(130);
        c.setCellFactory(col -> new TableCell<>() {
            private final Button revoke = button(REVOKE_LABEL, () -> { });

            {
                revoke.getStyleClass().add("danger-button");
                revoke.setOnAction(e -> {
                    TrustStore.Device d = getItem();
                    if (d != null) {
                        withAdmin("bir cihazı güvenden çıkarmak", () -> change(() -> ext.lanRevoke(d.fingerprint()),
                                removed -> message.setText("Güvenden çıkarıldı: " + removed.name() + " ("
                                        + DeviceIdentity.display(removed.fingerprint())
                                        + "). Yeniden bağlanmak için tekrar eşleştirilmesi gerekir.")));
                    }
                });
            }

            @Override
            protected void updateItem(TrustStore.Device d, boolean empty) {
                super.updateItem(d, empty);
                revoke.setText(ext.lanAdminUnlocked() ? REVOKE_LABEL : "🔒 " + REVOKE_LABEL);
                setGraphic(empty || d == null ? null : revoke);
                setAlignment(Pos.CENTER);
            }
        });
        return c;
    }

    // ================================================================== small builders

    private static Label label(String style) {
        Label l = new Label();
        l.getStyleClass().add(style);
        wrap(l);
        return l;
    }

    /** Wrapped text grows in height instead of being cut off with an ellipsis. */
    private static void wrap(Label l) {
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("shortcut-default");
        wrap(l);
        return l;
    }

    private static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("ghost-button");
        b.setOnAction(e -> action.run());
        return b;
    }
}
