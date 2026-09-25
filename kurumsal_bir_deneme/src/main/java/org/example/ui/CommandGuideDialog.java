package org.example.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.util.Duration;
import org.example.core.SystemFixture;

import java.util.List;
import java.util.Objects;

/**
 * "Komut Rehberi": a non-modal cheat-sheet for the command bar, topped by a 30-second sandbox scenario.
 *
 * <ul>
 *   <li><b>Sandbox</b> — Kudret's two-step {@code find} tour. "Örnek Aramayı Çubuğa Aktar ve Gör" places
 *   {@link #DEMO_QUERY} in the command bar; its match, {@link SystemFixture}, is always indexed as a hidden system
 *   document, so the tour works in any workspace.
 *   "Kendi Aramanı Yapmak İçin Şablon Bırak" places {@code find ""} with the caret between the quotes. Both stay in
 *   local BM25 mode: no AI, no API key.</li>
 *   <li><b>Commands</b> — every row has "Kopyala" (system clipboard) and "Dene" (fills the command bar, focuses it
 *   and closes the guide; nothing is submitted, the user presses Enter).</li>
 *   <li><b>Shortcuts</b> — a fixed reference table.</li>
 * </ul>
 *
 * <p>The dialog knows nothing about the controller: the chassis supplies the placement callback.</p>
 */
public final class CommandGuideDialog extends Dialog<Void> {

    /** The local BM25 search "Örnek Aramayı Çubuğa Aktar ve Gör" puts in the command bar. */
    public static final String DEMO_QUERY = "find \"mustafa bey haziran 2025 verileri\"";

    /** The template "Kendi Aramanı Yapmak İçin Şablon Bırak" puts in the command bar. */
    private static final String OWN_QUERY = "find \"\"";

    /** Caret position in {@link #OWN_QUERY}: between the quotes. */
    private static final int OWN_QUERY_CARET = 6;

    /** Receives a line to place in the command bar and the caret position inside it. */
    @FunctionalInterface
    public interface Placement {
        void place(String line, int caret);
    }

    /** One command row. {@code syntax} and {@code example} are {@code null} for argument-less commands. */
    private record Command(String name, String description, String syntax, String example) {
        /** What "Dene" places and "Kopyala" copies: the example, or the bare command. */
        String sample() {
            return example != null ? example : name;
        }
    }

    /** One shortcut row. */
    private record Shortcut(String keys, String description) {
    }

    private static final List<Command> COMMANDS = List.of(
            new Command("find", "aradığını bulma",
                    "find \"[aranan şey]\"", "find \"mustafa bey haziran 2025 verileri\""),
            new Command("ask", "soru sorma yapay zekaya",
                    "ask \"[sorunuz]\"", "ask \"alinin 2024 haziran ayındaki performansını özetle\""),
            new Command("web", "internette arama yapma",
                    "web \"[sorunuz]\"", "web \"kar marjını arttırmanın yolları\""),
            new Command("docs", "o an sisteme eklenmiş ve taranmış tüm belgelerin listesini döker", null, null),
            new Command("add", "belge veya klasör ekleyip indeksler; yol yazmazsanız dosya seçici açılır "
                    + "(Ctrl+O ile de açılır, sürükle-bırak da çalışır)",
                    "add [\"dosya veya klasör yolu\"]", "add \"C:\\Belgeler\\2025 raporlar\""),
            new Command("open", "dosya açma",
                    "open \"[dosya adı veya sıra no]\"", "open \"2024 mayıs aylık gelir gider tablosu\""),
            new Command("remove", "dosya silme(bilgisayarınızın içinden değil.onun için özel izin lazım)",
                    "remove \"[sileceğiniz dosya ismi]\"", "remove \"2012 eski gelir gider tablosu\""),
            new Command("stats",
                    "Sistemde kaç belge ve parça olduğunu, ne kadar bellek (RAM) kullanıldığını gösterir", null, null),
            new Command("clear", "Terminal ekranındaki önceki yazı ve çıktıları temizler", null, null),
            new Command("focus", "odak modu; odak modundayken hangi bildirimlerin görüneceğini de seçer "
                    + "(user_actions_only: yalnızca sizin başlattığınız işlemlerin sonuçları — varsayılan; "
                    + "all: tümü). Seçim projeyle birlikte saklanır",
                    "focus [--on|--off] [--notifications user_actions_only|all]",
                    "focus --notifications all"),
            new Command("find --in", "aramayı bir klasör, dosya türü, tarih, etiket ile sınırlama; -kelime hariç tutar",
                    "find [aranan] --in [pdf|klasör] --since [yyyy-aa-gg] --tag [etiket]",
                    "find kira artışı --in pdf --since 2025-01-01 -iade"),
            new Command("near", "iki kelimenin birbirine yakın geçtiği yerler (varsayılan 10 kelime)",
                    "near \"[kelime]\" \"[kelime]\" [N]", "near \"kira\" \"artış\" 10"),
            new Command("show", "sonucun geçtiği paragrafı öncesi/sonrasıyla gösterir (dosyayı açmadan)",
                    "show [sıra no] --context [N]", "show 1 --context 2"),
            new Command("cite", "kaynak gösterimini (dosya, sayfa, parça) panoya kopyalar", "cite [sıra no]", "cite 1"),
            new Command("export", "sonuç listesini Excel (CSV) veya Markdown dosyasına yazar",
                    "export \"[aranan]\" --csv|--md", "export \"kira artışı\" --csv"),
            new Command("similar", "seçili belgeye benzeyen diğer belgeler", "similar [sıra no]", "similar 1"),
            new Command("why", "bir sonucun neden bu sırada çıktığını açıklar", "why [sıra no]", "why 1"),
            new Command("saved", "son aramayı adıyla kaydeder, sonra tekrar çalıştırır",
                    "saved --add [ad] | saved [ad]", "saved --add aylik-kontrol"),
            new Command("ask --dry-run", "yapay zekâya ne gönderileceğini gösterir; çağırmaz, kota harcamaz",
                    "ask --dry-run [soru]  (--only [dosya|klasör] ile tek belgeye sınırlanır)",
                    "ask --dry-run kira artış oranı nedir"),
            new Command("stale", "değişmiş veya silinmiş kaynakları listeler (--reindex ile yeniden okur)", null, null),
            new Command("duplicates", "aynı adla birden fazla sürümü olan belgeler", null, null),
            new Command("ocr-needed", "taranmış (metinsiz) sayfaları olan PDF'ler; bunlar aranamaz", null, null),
            new Command("recent", "son açılan / önizlenen belgeler", null, null),
            new Command("pin", "belgeyi yer imine ekler (pins ile listelenir, unpin ile kaldırılır)",
                    "pin [sıra no]", "pin 1"),
            new Command("tag", "belgeye etiket verir (tags ile listelenir, find … --tag ile aranır)",
                    "tag [sıra no] [etiket]", "tag 1 sözleşme"),
            new Command("quota", "kalan yapay zekâ hakkını ve sıfırlanma zamanını gösterir", null, null),
            new Command("index --verify", "kayıtlı indeks dosyasının sağlamlığını kontrol eder", null, null),
            new Command("watch --list", "izlenen klasörleri ve son değişiklikleri gösterir", null, null));

    private static final List<Shortcut> SHORTCUTS = List.of(
            new Shortcut("boşluk yada f3", "seçili dosyanın önizlemesini açar"),
            new Shortcut("ctrl+f", "doğrudan yukarıdaki arama kısmını tetikler"),
            new Shortcut("ctrl+j", "terminali açma"),
            new Shortcut("ctrl+m", "arayüz modu arasında geçiş yapma"),
            new Shortcut("ctrl+shift+f", "odak moduna geçme"),
            new Shortcut("ctrl+o", "belge ekleme penceresini açar (odak modunda da çalışır)"),
            new Shortcut("esc", "açık olan önizlemeyi kapatma, sonra odak modundan çıkma"));

    private final Placement onTry;

    /**
     * @param onTry places a line in the command bar and focuses it (called after the guide has closed)
     */
    public CommandGuideDialog(Placement onTry) {
        this.onTry = Objects.requireNonNull(onTry, "onTry must not be null");
        initModality(Modality.NONE);
        setTitle("Komut Rehberi");

        VBox body = new VBox(18, sandbox(), commands(), shortcuts());
        body.getStyleClass().add("guide-body");

        ScrollPane scroll = new ScrollPane(body);
        scroll.getStyleClass().add("guide-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(660);
        scroll.setPrefViewportHeight(560);
        getDialogPane().getStyleClass().add("command-guide");
        getDialogPane().setContent(scroll);
        getDialogPane().getButtonTypes().setAll(new ButtonType("Kapat", ButtonBar.ButtonData.CANCEL_CLOSE));
        setResizable(true);
    }

    // ================================================================== sandbox

    private VBox sandbox() {
        Label badge = new Label("30 SANİYELİK MİNİ TUR");
        badge.getStyleClass().add("guide-sandbox-badge");

        Label title = new Label("30 Saniyede Gör: Bu Sistem Ne İşe Yarar?");
        title.getStyleClass().add("guide-sandbox-title");
        title.setWrapText(true);

        Label context = new Label("Kudret gibi bir ofis çalışanı olduğunu düşün: Yüzlerce dosya arasında acilen "
                + "'Mustafa Bey'in Haziran 2025 verilerini' bulman gerekiyor. Klasör klasör gezmek yerine arama "
                + "motoru gibi tek satırla nasıl bulacağını 30 saniyede test et.");
        context.getStyleClass().add("guide-sandbox-text");
        context.setWrapText(true);

        VBox steps = new VBox(6,
                step("Adım 1", "Arama çubuğuna find \"aranacak kelime\" kalıbını yazarsın."),
                step("Adım 2", "Enter'a bastığın an sistem yerel diskteki tüm belgeleri tarar ve en alakalı olanı "
                        + "milisaniyede önüne getirir."));
        steps.getStyleClass().add("guide-sandbox-steps");

        Button demo = new Button("Örnek Aramayı Çubuğa Aktar ve Gör");
        demo.getStyleClass().add("guide-sandbox-primary");
        demo.setTooltip(new Tooltip(DEMO_QUERY + " arama çubuğuna yazılır. Yerel BM25 araması: yapay zekâ ve "
                + "API anahtarı gerekmez, Enter'a basın"));
        demo.setOnAction(e -> {
            close();
            onTry.place(DEMO_QUERY, DEMO_QUERY.length());
        });
        demo.setDefaultButton(true);

        Button own = new Button("Kendi Aramanı Yapmak İçin Şablon Bırak");
        own.getStyleClass().addAll("ghost-button", "guide-sandbox-secondary");
        own.setTooltip(new Tooltip("Arama çubuğuna find \"\" yazılır; imleç tırnakların arasında olur"));
        own.setOnAction(e -> {
            close();
            onTry.place(OWN_QUERY, OWN_QUERY_CARET);
        });

        HBox buttons = new HBox(8, demo, own);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, badge, title, context, steps, buttons);
        card.getStyleClass().add("guide-sandbox");
        return card;
    }

    /** One numbered tutorial step: a small badge and its wrapped explanation. */
    private static HBox step(String number, String text) {
        Label badge = new Label(number);
        badge.getStyleClass().add("guide-sandbox-step-number");
        badge.setMinWidth(Region.USE_PREF_SIZE);
        Label body = new Label(text);
        body.getStyleClass().add("guide-sandbox-step-text");
        body.setWrapText(true);
        HBox.setHgrow(body, Priority.ALWAYS);
        HBox row = new HBox(10, badge, body);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("guide-sandbox-step");
        return row;
    }

    // ================================================================== commands

    private VBox commands() {
        VBox list = new VBox(8);
        for (Command c : COMMANDS) {
            list.getChildren().add(commandCard(c));
        }
        return new VBox(8, title("KOMUTLAR"), list);
    }

    private HBox commandCard(Command c) {
        Label name = new Label(c.name());
        name.getStyleClass().add("guide-command-name");
        name.setMinWidth(Region.USE_PREF_SIZE);

        Label description = new Label(c.description());
        description.getStyleClass().add("guide-description");
        description.setWrapText(true);

        HBox head = new HBox(10, name, description);
        head.setAlignment(Pos.BASELINE_LEFT);
        HBox.setHgrow(description, Priority.ALWAYS);
        VBox text = new VBox(4, head);
        if (c.syntax() != null) {
            text.getChildren().add(labelled("Syntax:", c.syntax()));
        }
        if (c.example() != null) {
            text.getChildren().add(labelled("Örnek:", c.example()));
        }
        if (c.syntax() == null && c.example() == null) {
            text.getChildren().add(labelled("Komut:", c.name()));
        }
        HBox.setHgrow(text, Priority.ALWAYS);

        Button copy = action("Kopyala", "Panoya kopyala: " + c.sample(), null);
        copy.setOnAction(e -> copy(copy, c.sample()));
        Button attempt = action("Dene", "Arama çubuğuna yerleştir: " + c.sample(), () -> {
            close();
            onTry.place(c.sample(), c.sample().length());
        });
        attempt.getStyleClass().add("guide-try");
        VBox buttons = new VBox(4, copy, attempt);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setMinWidth(Region.USE_PREF_SIZE);
        copy.setMaxWidth(Double.MAX_VALUE);
        attempt.setMaxWidth(Double.MAX_VALUE);

        HBox card = new HBox(12, text, buttons);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("guide-command");
        return card;
    }

    private static HBox labelled(String caption, String code) {
        Label key = new Label(caption);
        key.getStyleClass().add("guide-caption");
        key.setMinWidth(52);
        Label value = new Label(code);
        value.getStyleClass().add("guide-code");
        value.setWrapText(true);
        HBox row = new HBox(6, key, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Copies {@code text} to the system clipboard and confirms on the button for a moment. */
    private static void copy(Button button, String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        button.setText("Kopyalandı ✓");
        PauseTransition reset = new PauseTransition(Duration.millis(1200));
        reset.setOnFinished(e -> button.setText("Kopyala"));
        reset.play();
    }

    // ================================================================== shortcuts

    private static VBox shortcuts() {
        GridPane grid = new GridPane();
        grid.getStyleClass().addAll("guide-grid", "guide-shortcuts");
        grid.setHgap(14);
        grid.setVgap(8);
        int row = 0;
        for (Shortcut s : SHORTCUTS) {
            Label keys = new Label(s.keys());
            keys.getStyleClass().add("guide-key");
            keys.setMinWidth(Region.USE_PREF_SIZE);
            Label description = new Label(s.description());
            description.getStyleClass().add("guide-description");
            description.setWrapText(true);
            GridPane.setHgrow(description, Priority.ALWAYS);
            grid.addRow(row++, keys, description);
        }
        return new VBox(8, title("KLAVYE KISAYOLLARI"), grid);
    }

    // ================================================================== helpers

    private static Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    private static Button action(String text, String tip, Runnable run) {
        Button b = new Button(text);
        b.getStyleClass().addAll("ghost-button", "guide-action");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tip));
        if (run != null) {
            b.setOnAction(e -> run.run());
        }
        return b;
    }
}
