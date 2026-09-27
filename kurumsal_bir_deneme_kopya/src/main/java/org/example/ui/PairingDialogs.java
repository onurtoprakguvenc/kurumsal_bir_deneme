package org.example.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.util.Duration;
import org.example.p2p.DeviceIdentity;
import org.example.p2p.DeviceRole;
import org.example.p2p.FileTransferService;
import org.example.p2p.LanSyncService;
import org.example.p2p.TrustStore;
import org.example.p2p.ZeroTrust;
import org.example.workbench.ExtendedWorkbenchController;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The two halves of zero-trust onboarding as modal dialogs.
 *
 * <ul>
 *   <li>{@link PinDialog} ("Yeni Cihaz Eşleştir", on an already trusted computer): one click opens a 6-digit PIN,
 *       valid 5 minutes for one attempt, with a countdown and this computer's addresses. When the new device enters
 *       it, the dialog switches to the verification code the new device must show as well.</li>
 *   <li>{@link JoinDialog} ("Bir Cihaza Katıl", on the new computer): address (nearby unpaired devices are offered)
 *       and PIN; afterwards the same verification code screen.</li>
 * </ul>
 * <p>Both end with an explicit decision: "Kodlar aynı" keeps the device, "Kodlar farklı" revokes it at once (someone
 * was in between). Every action goes through {@link ExtendedWorkbenchController}, which audits it; network work runs
 * off the FX thread.</p>
 */
final class PairingDialogs {

    private PairingDialogs() {
    }

    /** "123 456" in large type. */
    private static Label bigCode(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("pairing-code");
        return l;
    }

    private static Label text(String value) {
        Label l = new Label(value);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE); // wrapped text grows instead of being cut off
        l.getStyleClass().add("admin-status");
        return l;
    }

    private static Button action(String label, Runnable run) {
        Button b = new Button(label);
        b.getStyleClass().add("ghost-button");
        b.setOnAction(e -> run.run());
        return b;
    }

    private static String roleText(DeviceRole role) {
        return role == DeviceRole.RESTRICTED_GUEST ? "Kısıtlı misafir (RESTRICTED_GUEST)" : "Tam eş (FULL_PEER)";
    }

    /**
     * The shared last step: device, fingerprint, the verification code, and the keep/revoke decision.
     *
     * @param done called after either decision (closes the dialog)
     */
    private static VBox verification(ExtendedWorkbenchController ext, TrustStore.Device device, String code,
                                     Label status, Runnable done) {
        Label title = new Label("Doğrulama kodu");
        title.getStyleClass().add("section-title");
        Button same = action("✔ Kodlar aynı — Onayla", done);
        same.setDefaultButton(true);
        Button differ = action("✖ Kodlar farklı — Güvenden çıkar", () -> CompletableFuture.runAsync(() -> {
            try {
                // The undo of this very pairing: always allowed, unlike revoking an established device.
                ext.lanRejectPairing(device.fingerprint());
                Fx.run(done);
            } catch (Exception e) {
                Fx.run(() -> status.setText("Kaldırılamadı: " + e.getMessage()));
            }
        }));
        differ.getStyleClass().add("danger-button");
        return new VBox(10,
                text("Eşleşti: " + device.name() + " · " + roleText(device.role())
                        + (device.department().isEmpty() ? "" : " · departman " + device.department())),
                text("Cihaz parmak izi: " + DeviceIdentity.display(device.fingerprint())),
                title, bigCode(code),
                text("Diğer ekranda da tam olarak bu kod görünmelidir. Kod farklıysa bağlantıya biri araya girmiştir:"
                        + " cihazı güvenden çıkarın ve yeniden eşleştirin."),
                new HBox(8, same, differ), status);
    }

    // ================================================================== PIN side

    /** "Yeni Cihaz Eşleştir": opens a PIN on this computer and waits for the new device. */
    static final class PinDialog extends Dialog<Void> {
        private final ExtendedWorkbenchController ext;
        private final DeviceRole role;
        private final String department;
        private final VBox body = new VBox(12);
        private final Label countdown = text("");
        private final Label status = text("");
        private final Timeline ticker = new Timeline();
        private final Button retry = action("Yeni PIN üret", this::openPin);
        private Runnable unsubscribe = () -> { };
        private ZeroTrust.PairingTicket ticket;
        private boolean paired;

        PinDialog(ExtendedWorkbenchController ext, DeviceRole role, String department) {
            this.ext = ext;
            this.role = role;
            this.department = department;
            initModality(Modality.APPLICATION_MODAL);
            setTitle("Yeni Cihaz Eşleştir");
            setHeaderText("Tek kullanımlık eşleştirme PIN'i · " + roleText(role));
            body.setPadding(new Insets(14));
            body.setPrefWidth(520);
            status.getStyleClass().add("admin-warning");
            getDialogPane().setContent(body);
            getDialogPane().getButtonTypes().setAll(new ButtonType("Kapat", ButtonBar.ButtonData.CANCEL_CLOSE));
            ticker.getKeyFrames().add(new KeyFrame(Duration.seconds(1), e -> tick()));
            ticker.setCycleCount(Animation.INDEFINITE);
            unsubscribe = ext.onLanPairing(new ZeroTrust.PairingListener() {
                @Override
                public void paired(TrustStore.Device device, String code) {
                    Fx.run(() -> showPaired(device, code));
                }

                @Override
                public void failed(String reason) {
                    Fx.run(() -> showFailed(reason));
                }
            });
            setOnHidden(e -> {
                ticker.stop();
                unsubscribe.run();
                if (!paired && ext.zeroTrust().flatMap(ZeroTrust::pendingPairing).isPresent()) {
                    ext.lanCancelPairing(); // nobody may use a PIN whose window was closed
                }
            });
            openPin();
        }

        private void openPin() {
            status.setText("");
            try {
                ticket = ext.lanOpenPairing(role, department);
            } catch (org.example.admin.AdminControlEngine.PrivilegeException e) {
                body.getChildren().setAll(text("🔒 Yönetici kilidi kapalı: yeni cihaz eşleştirmek için yönetici oturumu"
                        + " gerekir (Yönetim Paneli → Genel → Kilidi Aç)."));
                return;
            } catch (RuntimeException e) {
                body.getChildren().setAll(text("PIN açılamadı: " + e.getMessage()));
                return;
            }
            setHeaderText("Tek kullanımlık eşleştirme PIN'i · " + roleText(ticket.role())
                    + (ticket.department().isEmpty() ? "" : " · departman " + ticket.department()));
            int port = ext.lanShield().transferPort();
            List<String> addresses = LanSyncService.lanAddresses();
            String where = addresses.isEmpty() ? "bu bilgisayarın adresi:" + port
                    : String.join("  veya  ", addresses.stream().map(a -> a + ":" + port).toList());
            Label waiting = text("Yeni cihazda: Yönetim Paneli → Cihazlar → “Bir Cihaza Katıl”. Adres: " + where
                    + " · ardından bu PIN'i girin.");
            Label pinTitle = new Label("EŞLEŞTİRME PIN'İ");
            pinTitle.getStyleClass().add("section-title");
            body.getChildren().setAll(pinTitle, bigCode(ticket.pin().substring(0, 3) + " " + ticket.pin().substring(3)),
                    countdown, waiting, text("PIN tek denemeliktir: yanlış girilirse geçersiz olur. Karşı cihaz PIN'i"
                            + " girdiğinde doğrulama kodu burada görünecek."), status);
            tick();
            ticker.playFromStart();
        }

        private void tick() {
            if (ticket == null || paired) {
                return;
            }
            long left = java.time.Duration.between(Instant.now(), ticket.expires()).toSeconds();
            if (left <= 0) {
                ticker.stop();
                countdown.setText("Süre doldu.");
                showRetry("PIN'in süresi doldu; kullanılmadı.");
                return;
            }
            countdown.setText(String.format("Kalan süre: %d:%02d · bekleniyor…", left / 60, left % 60));
        }

        private void showPaired(TrustStore.Device device, String code) {
            paired = true;
            ticker.stop();
            setHeaderText("Cihaz eşleşti — kodları karşılaştırın");
            body.getChildren().setAll(verification(ext, device, code, status, this::close));
        }

        private void showFailed(String reason) {
            if (paired) {
                return;
            }
            ticker.stop();
            countdown.setText("");
            showRetry("Eşleştirme başarısız: " + reason);
        }

        private void showRetry(String message) {
            status.setText(message);
            if (!body.getChildren().contains(retry)) {
                body.getChildren().add(retry);
            }
        }
    }

    // ================================================================== joining side

    /** "Bir Cihaza Katıl": enters the other computer's address and PIN on this (new) computer. */
    static final class JoinDialog extends Dialog<Void> {
        private final ExtendedWorkbenchController ext;
        private final VBox body = new VBox(12);
        private final Label status = text("");

        JoinDialog(ExtendedWorkbenchController ext) {
            this.ext = ext;
            initModality(Modality.APPLICATION_MODAL);
            setTitle("Bir Cihaza Katıl");
            setHeaderText("Yetkili bir bilgisayarın ürettiği PIN ile bu cihazı eşleştirin");
            body.setPadding(new Insets(14));
            body.setPrefWidth(520);
            status.getStyleClass().add("admin-warning");
            getDialogPane().setContent(body);
            getDialogPane().getButtonTypes().setAll(new ButtonType("Kapat", ButtonBar.ButtonData.CANCEL_CLOSE));
            form();
        }

        private void form() {
            ComboBox<String> address = new ComboBox<>();
            address.setEditable(true);
            address.setPromptText("192.168.1.6:47778 veya cihaz adı");
            address.setMaxWidth(Double.MAX_VALUE);
            for (ZeroTrust.Sighting s : ext.lanUnpaired()) {
                if (s.transferPort() > 0) {
                    address.getItems().add(s.endpoint() + "   " + s.name() + " · " + DeviceIdentity.display(s.fingerprint()));
                }
            }
            TextField pin = new TextField();
            pin.setPromptText("6 haneli PIN");
            pin.setPrefColumnCount(8);
            pin.setTextFormatter(new TextFormatter<String>(change ->
                    change.getControlNewText().matches("\\d{0,6}") ? change : null));
            Button join = action("Eşleştir", () -> { });
            join.setDefaultButton(true);
            join.setOnAction(e -> {
                String raw = address.getEditor().getText() == null ? "" : address.getEditor().getText().strip();
                String target = raw.contains(" ") ? raw.substring(0, raw.indexOf(' ')) : raw;
                if (target.isEmpty() || pin.getText().length() != 6) {
                    status.setText("Adres ve 6 haneli PIN gerekli.");
                    return;
                }
                join.setDisable(true);
                status.setText("Bağlanılıyor…");
                String code = pin.getText();
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return ext.lanJoin(target, code);
                    } catch (Exception ex) {
                        throw new java.util.concurrent.CompletionException(ex);
                    }
                }).whenComplete((result, error) -> Fx.run(() -> {
                    join.setDisable(false);
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        status.setText("Eşleştirilemedi: " + cause.getMessage());
                    } else {
                        showPaired(result);
                    }
                }));
            });
            HBox.setHgrow(address, Priority.ALWAYS);
            Label hint = text(address.getItems().isEmpty()
                    ? "Diğer bilgisayarda “Yeni Cihaz Eşleştir” ile bir PIN üretin; orada gösterilen adresi ve PIN'i"
                    + " buraya girin."
                    : "Ağda görülen eşleştirilmemiş cihazlar listede; birini seçin ya da adresi yazın.");
            body.getChildren().setAll(hint, new HBox(8, address), new HBox(8, pin, join), status);
            body.setAlignment(Pos.TOP_LEFT);
        }

        private void showPaired(FileTransferService.PairingResult result) {
            status.setText("");
            setHeaderText("Eşleşti — kodları karşılaştırın");
            body.getChildren().setAll(verification(ext, result.device(), result.verificationCode(), status, this::close));
        }
    }
}
