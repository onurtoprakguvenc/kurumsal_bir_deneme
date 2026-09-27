package org.example.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import org.example.net.WebSearchBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Top bar of the window, laid out like a file manager's address row:
 *
 * <pre>
 * [←] [→] [↑] [⟳]  Giriş › CMP3005 › Hafta 4 ··········   [BM25 ▾][ search field ][trailing]
 * [toolbar row: Yeni ▾ · Sırala ▾ · Görünüm ▾ · ⋯ ················ Terminal]
 * </pre>
 *
 * <p>The navigation buttons and the breadcrumb trail are driven by the chassis ({@link #setNavigation},
 * {@link #setCrumbs}, {@link #setNavigationEnabled}); this view only renders them. Together with the toolbar row they
 * are chrome, so focus mode removes them and the search field then takes the whole width.</p>
 *
 * <p>The search field is a unified input. The first characters of the line pick the route, and a chip in front of the field shows
 * which one Enter will take:
 *
 * <ul>
 *   <li>plain text → {@link Mode#SEARCH} (local BM25),</li>
 *   <li>{@code ? question} → {@link Mode#ASK} (grounded answer over the ephemeral context),</li>
 *   <li>{@code @web question} → {@link Mode#WEB} (metered hybrid web search),</li>
 *   <li>{@code > command} → {@link Mode#COMMAND} (a line for the internal terminal).</li>
 * </ul>
 *
 * <p>The chip is also a button: clicking it (or Alt+Down in the field) opens a mode picker that rewrites the line's
 * prefix, so every route is reachable without knowing the prefixes. Below the input sits an optional toolbar row the
 * chassis fills with its actions; it is chrome, so focus mode removes it. The trailing slot right of the field is
 * never hidden (it holds the focus-mode switch, which must stay reachable in focus mode).</p>
 *
 * <p>The chip only swaps a style class when the mode actually changes, so typing costs no CSS pass.</p>
 */
public final class CommandBarView extends VBox {

    public enum Mode {
        SEARCH("BM25", "mode-search", "", "Belgelerde ara", "Yerel BM25 araması (varsayılan)"),
        ASK("ASK ?", "mode-ask", "? ", "Soru sor", "Belgelerinize dayanan yanıt"),
        WEB("@WEB", "mode-web", WebSearchBridge.PREFIX + " ", "Web'de ara", "Ölçümlü web + yapay zekâ araması"),
        COMMAND("CMD >", "mode-command", "> ", "Komut çalıştır", "Satırı dahili terminale gönder");

        private final String label;
        private final String styleClass;
        private final String prefix;
        private final String title;
        private final String description;

        Mode(String label, String styleClass, String prefix, String title, String description) {
            this.label = label;
            this.styleClass = styleClass;
            this.prefix = prefix;
            this.title = title;
            this.description = description;
        }

        public String label() {
            return label;
        }

        /** Text typed in front of a line to route it here ({@code ""} for plain search). */
        public String prefix() {
            return prefix;
        }

        public String title() {
            return title;
        }

        public String description() {
            return description;
        }

        /** Route for {@code line}; the prefix (or a guide verb, see {@link #normalize}) decides, never the content. */
        public static Mode of(String line) {
            String s = normalize(line);
            if (s.startsWith("?")) {
                return ASK;
            }
            if (s.startsWith(">")) {
                return COMMAND;
            }
            return WebSearchBridge.isWebQuery(s) ? WEB : SEARCH;
        }

        /** {@code line} without its routing prefix. */
        public static String body(String line) {
            String s = normalize(line);
            return switch (of(s)) {
                case ASK, COMMAND -> s.substring(1).stripLeading();
                case WEB -> WebSearchBridge.stripPrefix(s);
                case SEARCH -> s;
            };
        }

        /**
         * Rewrites the verb forms documented in the command guide into their prefix form, so
         * {@code ask "q"} → {@code ? q}, {@code web "q"} → {@code @web q}, {@code find "q"} → {@code q} (always a
         * local BM25 search: an unclosed quote is tolerated and routing prefixes inside the phrase are dropped) and
         * {@code docs | stats | clear | open "x" | remove "x"} → {@code > …}. Anything else is returned unchanged
         * (leading blanks stripped). {@code open} and {@code remove} need a quoted or numeric argument, and
         * {@code docs}/{@code stats}/{@code clear} must stand alone (flags allowed), so ordinary searches such as
         * {@code open source lisans} stay searches.
         */
        public static String normalize(String line) {
            String s = line == null ? "" : line.stripLeading();
            int space = indexOfWhitespace(s);
            String verb = (space < 0 ? s : s.substring(0, space)).toLowerCase(Locale.ROOT);
            String rest = space < 0 ? "" : s.substring(space).strip();
            return switch (verb) {
                // "--dry-run" / "--only" need the terminal command; a plain question stays an AI answer
                case "ask" -> rest.isEmpty() ? s : hasOption(rest, "dry-run", "only") ? "> " + s : "? " + unquote(rest);
                case "web" -> rest.isEmpty() ? s : WebSearchBridge.PREFIX + " " + unquote(rest);
                // scope flags (--in, --type, …) need the terminal command; a plain phrase stays a BM25 search
                case "find" -> rest.isEmpty() ? s : hasOption(rest, SCOPE_FLAGS) ? "> " + s : searchPhrase(rest);
                case "docs", "stats", "clear", "recent", "pins", "tags", "stale", "duplicates", "ocr-needed", "quota",
                     "saved" -> rest.isEmpty() || rest.startsWith("--") ? "> " + s : s;
                case "open", "remove" -> isQuoted(rest) || isRow(rest) ? "> " + s : s;
                case "show", "cite", "why", "similar", "pin", "unpin", "tag", "untag" ->
                        isQuoted(rest) || isRow(firstWord(rest)) ? "> " + s : s;
                case "near", "export" -> isQuoted(firstWord(rest)) ? "> " + s : s;
                case "index", "watch" -> rest.startsWith("--") ? "> " + s : s;
                default -> s;
            };
        }

        private static final String[] SCOPE_FLAGS = {"in", "type", "since", "until", "tag", "pinned", "exclude",
                "top"};

        private static boolean hasOption(String rest, String... names) {
            for (String word : rest.split("\\s+")) {
                if (word.startsWith("--")) {
                    String name = word.substring(2).toLowerCase(Locale.ROOT);
                    int eq = name.indexOf('=');
                    String bare = eq < 0 ? name : name.substring(0, eq);
                    for (String n : names) {
                        if (n.equals(bare)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /** First argument: a quoted phrase as a whole, else the first word. */
        private static String firstWord(String rest) {
            if (rest.startsWith("\"") || rest.startsWith("'")) {
                int close = rest.indexOf(rest.charAt(0), 1);
                return close < 0 ? rest : rest.substring(0, close + 1);
            }
            int space = indexOfWhitespace(rest);
            return space < 0 ? rest : rest.substring(0, space);
        }

        private static int indexOfWhitespace(String s) {
            for (int i = 0; i < s.length(); i++) {
                if (Character.isWhitespace(s.charAt(i))) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean isQuoted(String s) {
            return s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')
                    && s.charAt(s.length() - 1) == s.charAt(0);
        }

        private static boolean isRow(String s) {
            return !s.isEmpty() && s.length() <= 4 && s.chars().allMatch(Character::isDigit);
        }

        /**
         * The phrase of {@code find …} as a plain BM25 query: surrounding quotes are removed (also a lone opening
         * quote while the user is still typing), and a leading {@code ?}, {@code >} or {@code @web} is dropped so
         * the phrase can never re-route the line to ASK, COMMAND or WEB.
         */
        private static String searchPhrase(String rest) {
            String s = unquote(rest);
            if (!isQuoted(rest) && (rest.startsWith("\"") || rest.startsWith("'"))) {
                s = rest.substring(1).strip();
            }
            while (true) {
                if (s.startsWith("?") || s.startsWith(">")) {
                    s = s.substring(1).stripLeading();
                } else if (WebSearchBridge.isWebQuery(s)) {
                    s = WebSearchBridge.stripPrefix(s);
                } else {
                    return s;
                }
            }
        }

        /** {@code "text"} → {@code text}; an unmatched or missing quote leaves the text as it is. */
        private static String unquote(String s) {
            return isQuoted(s) ? s.substring(1, s.length() - 1).strip() : s;
        }

        /** {@code line} re-routed to {@code target}: the old prefix is replaced, the text itself kept. */
        public static String rewrite(String line, Mode target) {
            return target.prefix + body(line);
        }
    }

    /** Receives a submitted, non-blank line together with the mode it was routed to. */
    @FunctionalInterface
    public interface SubmitHandler {
        void submitted(Mode mode, String line);
    }

    /** One breadcrumb segment; {@code action} is {@code null} for the current (last) location. */
    public record Crumb(String label, String tooltip, Runnable action) {
        public Crumb {
            Objects.requireNonNull(label, "label must not be null");
        }
    }

    /** What the navigation buttons ask of the chassis. */
    public interface Navigation {
        void back();

        void forward();

        void up();

        void refresh();
    }

    private static final int HISTORY = 50;
    /** Longer trails keep their first crumb and the last {@value} ones, joined by an ellipsis. */
    private static final int CRUMB_TAIL = 3;
    private static final double FIELD_WIDTH = 380;
    private static final double FIELD_MAX_WIDTH = 520;
    /** Marks the header while the action bar is hidden, so the input can carry the larger reading size. */
    private static final String FOCUS_STYLE_CLASS = "focus-mode";

    private final TextField field = new TextField();
    private final Label chip = new Label();
    private final HBox trailing = new HBox(6);
    private final Button back = navButton("←", "Geri (Alt+←)");
    private final Button forward = navButton("→", "İleri (Alt+→)");
    private final Button up = navButton("↑", "Üst klasör (Alt+↑)");
    private final Button reload = navButton("⟳", "Yenile");
    private final HBox nav = new HBox(2, back, forward, up, reload);
    private final HBox crumbs = new HBox(0);
    private final HBox row;
    private final HBox toolbar = new HBox(6);
    private final ContextMenu modeMenu = new ContextMenu();
    private final List<String> history = new ArrayList<>();
    private int historyCursor;
    private Mode mode;
    /**
     * Set when the user deleted the {@code >} of a command line that still has text: the line stays a command (chip
     * and Enter) instead of silently falling back to a BM25 search of the half-edited command. Cleared when the line
     * becomes blank, another prefix is typed, or a mode is picked explicitly.
     */
    private boolean commandHeld;
    /** True while this view replaces the whole line itself (history, mode picker, placed commands). */
    private boolean internalEdit;
    private boolean chromeVisible = true;
    private SubmitHandler onSubmit = (m, l) -> { };
    private Runnable onLeaveDown = () -> { };
    private Navigation navigation = new Navigation() {
        @Override
        public void back() {
        }

        @Override
        public void forward() {
        }

        @Override
        public void up() {
        }

        @Override
        public void refresh() {
        }
    };

    public CommandBarView() {
        getStyleClass().add("command-container");
        setPadding(new Insets(10, 14, 8, 14));
        setSpacing(8);

        field.getStyleClass().add("command-bar");
        field.setPromptText("Belgelerde ara…   ? soru   @web   > komut");
        field.setPrefWidth(FIELD_WIDTH);
        field.setMaxWidth(FIELD_MAX_WIDTH);
        field.setMinWidth(160);

        chip.getStyleClass().add("mode-chip");
        chip.setMinWidth(USE_PREF_SIZE);
        chip.setTooltip(new Tooltip("Arama modunu seç (Alt+↓)"));
        chip.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                showModeMenu();
            }
        });
        buildModeMenu();

        trailing.setAlignment(Pos.CENTER_RIGHT);
        trailing.setMinWidth(USE_PREF_SIZE);

        nav.getStyleClass().add("nav-buttons");
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.setMinWidth(USE_PREF_SIZE);
        back.setOnAction(e -> navigation.back());
        forward.setOnAction(e -> navigation.forward());
        up.setOnAction(e -> navigation.up());
        reload.setOnAction(e -> navigation.refresh());
        setNavigationEnabled(false, false, false);

        crumbs.getStyleClass().add("breadcrumb-bar");
        crumbs.setAlignment(Pos.CENTER_LEFT);
        crumbs.setMinWidth(0);
        crumbs.setPrefWidth(0); // takes whatever the row leaves over; never widens the window
        crumbs.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(crumbs, Priority.ALWAYS);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(crumbs.widthProperty());
        clip.heightProperty().bind(crumbs.heightProperty());
        crumbs.setClip(clip);

        row = new HBox(8, nav, crumbs, chip, field, trailing);
        row.setMinWidth(0);
        row.getStyleClass().add("command-row");
        row.setAlignment(Pos.CENTER_LEFT);

        toolbar.getStyleClass().add("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(row);

        applyMode(Mode.SEARCH);
        field.textProperty().addListener((obs, old, text) -> onText(old, text));
        // A filter, so the field's own behaviour (and the scene-level dispatcher) never sees these keys first.
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    private static Button navButton(String glyph, String tooltip) {
        Button b = new Button(glyph);
        b.getStyleClass().add("nav-button");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tooltip));
        return b;
    }

    private void buildModeMenu() {
        modeMenu.getStyleClass().add("mode-menu");
        for (Mode m : Mode.values()) {
            Label badge = new Label(m.label());
            badge.getStyleClass().addAll("mode-chip", m.styleClass);
            badge.setMinWidth(64);
            Label title = new Label(m.title());
            title.getStyleClass().add("mode-menu-title");
            Label description = new Label(m.description()
                    + (m.prefix().isBlank() ? "" : "   ·   önek: " + m.prefix().strip()));
            description.getStyleClass().add("mode-menu-description");
            HBox item = new HBox(10, badge, new VBox(1, title, description));
            item.setAlignment(Pos.CENTER_LEFT);
            CustomMenuItem menuItem = new CustomMenuItem(item, true);
            menuItem.setOnAction(e -> selectMode(m));
            modeMenu.getItems().add(menuItem);
        }
    }

    private void showModeMenu() {
        if (field.isDisabled()) {
            return;
        }
        modeMenu.show(chip, Side.BOTTOM, 0, 4);
    }

    private void onKey(KeyEvent e) {
        if (e.isAltDown() && e.getCode() == KeyCode.DOWN) {
            showModeMenu();
            e.consume();
            return;
        }
        if (e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
            return;
        }
        KeyCode code = e.getCode();
        switch (code) {
            case ENTER -> {
                submit();
                e.consume();
            }
            case ESCAPE -> {
                if (!field.getText().isEmpty()) {
                    setLine("");
                    e.consume();
                }
            }
            case UP -> {
                recall(-1);
                e.consume();
            }
            case DOWN -> {
                if (historyCursor < history.size()) {
                    recall(+1);
                } else {
                    onLeaveDown.run();
                }
                e.consume();
            }
            default -> {
            }
        }
    }

    private void submit() {
        String line = field.getText().strip();
        if (line.isEmpty() || Mode.body(line).isBlank()) {
            return;
        }
        String normalized = Mode.normalize(line);
        boolean held = commandHeld && Mode.of(normalized) == Mode.SEARCH;
        if (held) {
            normalized = Mode.COMMAND.prefix() + normalized; // the chip said CMD: Enter runs it as a command
        }
        String remembered = redactSecrets(held ? normalized : line, normalized);
        if (history.isEmpty() || !history.getLast().equals(remembered)) {
            history.add(remembered);
            if (history.size() > HISTORY) {
                history.removeFirst();
            }
        }
        historyCursor = history.size();
        Mode routed = Mode.of(normalized);
        if (routed == Mode.COMMAND) {
            // Commands are one-shot; the prefix stays so the next line is a command too.
            setLine(Mode.COMMAND.prefix());
            field.end();
        }
        onSubmit.submitted(routed, normalized);
    }

    /**
     * The history form of a line: {@code admin unlock <secret>} and {@code admin passwd <new>} keep only their verb, so
     * a passphrase can never be recalled with the Up key (the terminal redacts them the same way).
     */
    static String redactSecrets(String line, String normalized) {
        if (Mode.of(normalized) != Mode.COMMAND) {
            return line;
        }
        String[] words = Mode.body(normalized).strip().split("\\s+");
        if (words.length > 2 && words[0].equalsIgnoreCase("admin")
                && (words[1].equalsIgnoreCase("unlock") || words[1].equalsIgnoreCase("passwd"))
                && !words[2].equalsIgnoreCase("--clear")) {
            return Mode.COMMAND.prefix() + "admin " + words[1].toLowerCase(Locale.ROOT) + " ";
        }
        return line;
    }

    private void recall(int step) {
        if (history.isEmpty()) {
            return;
        }
        historyCursor = Math.max(0, Math.min(history.size(), historyCursor + step));
        setLine(historyCursor == history.size() ? "" : history.get(historyCursor));
        field.end();
    }

    /** Replaces the whole line; its own prefix alone decides the mode (no held command). */
    private void setLine(String text) {
        internalEdit = true;
        try {
            commandHeld = false;
            field.setText(text);
        } finally {
            internalEdit = false;
        }
    }

    /**
     * Mode follows the typed prefix, with one exception: deleting the {@code >} of a command that still has text
     * keeps COMMAND until the line is cleared or re-routed explicitly, so a half-edited command never flips the chip
     * to BM25 (and Enter never runs it as an index query).
     */
    private void onText(String old, String text) {
        Mode routed = Mode.of(text);
        if (internalEdit || text == null || text.isBlank() || routed != Mode.SEARCH) {
            commandHeld = false;
        } else if (!commandHeld && mode == Mode.COMMAND && removedCommandPrefix(old, text)) {
            commandHeld = true;
        }
        applyMode(commandHeld ? Mode.COMMAND : routed);
    }

    /** Whether {@code text} is {@code old} with one contiguous range deleted that covered its leading {@code >}. */
    static boolean removedCommandPrefix(String old, String text) {
        if (old == null || text == null || text.length() >= old.length()) {
            return false;
        }
        int prefix = old.indexOf('>');
        if (prefix < 0 || !old.substring(0, prefix).isBlank()) {
            return false;
        }
        int removed = old.length() - text.length();
        for (int start = Math.max(0, prefix - removed + 1); start <= prefix; start++) {
            if ((old.substring(0, start) + old.substring(start + removed)).equals(text)) {
                return true;
            }
        }
        return false;
    }

    private void applyMode(Mode next) {
        if (next == mode) {
            return;
        }
        if (mode != null) {
            chip.getStyleClass().remove(mode.styleClass);
        }
        chip.getStyleClass().add(next.styleClass);
        chip.setText(next.label() + "  ▾");
        mode = next;
    }

    // ================================================================== API

    /** Re-routes the current line to {@code target} (keeping its text) and returns focus to the field. */
    public void selectMode(Mode target) {
        setLine(Mode.rewrite(field.getText(), target));
        field.requestFocus();
        field.end();
    }

    /** Places {@code line} in the field (its prefix selects the mode) with the caret at {@code caret}. */
    public void placeLine(String line, int caret) {
        setLine(line);
        field.requestFocus();
        field.positionCaret(Math.max(0, Math.min(caret, line.length())));
    }

    public void setOnSubmit(SubmitHandler handler) {
        this.onSubmit = Objects.requireNonNull(handler);
    }

    /** Called when Down is pressed past the end of the history (the chassis moves focus into the results). */
    public void setOnLeaveDown(Runnable action) {
        this.onLeaveDown = Objects.requireNonNull(action);
    }

    /** Toolbar row below the input (chrome: hidden in focus mode). An empty list removes the row. */
    public void setToolbarItems(List<? extends Node> items) {
        toolbar.getChildren().setAll(items);
        syncToolbar();
    }

    /** Controls right of the input; always visible, including in focus mode. */
    public void setTrailingItems(List<? extends Node> items) {
        trailing.getChildren().setAll(items);
    }

    /** A flexible gap for toolbar layouts (pushes the following items to the right). */
    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public void focusInput() {
        field.requestFocus();
        field.selectAll();
    }

    public Mode mode() {
        return mode;
    }

    /**
     * Shortcut summary; rebuilt whenever key bindings change. Neither row has room for it as a label — in focus mode
     * the header is reduced to the picker, the input and the focus switch — so it lives on as the field's tooltip.
     */
    public void setHint(String text) {
        field.setTooltip(text == null || text.isBlank() ? null : new Tooltip(text));
    }

    public void setNavigation(Navigation navigation) {
        this.navigation = Objects.requireNonNull(navigation);
    }

    public void setNavigationEnabled(boolean canBack, boolean canForward, boolean canUp) {
        back.setDisable(!canBack);
        forward.setDisable(!canForward);
        up.setDisable(!canUp);
    }

    /** Replaces the breadcrumb trail; the last crumb is the current location and is not clickable. */
    public void setCrumbs(List<Crumb> trail) {
        List<Node> nodes = new ArrayList<>(trail.size() * 2);
        int n = trail.size();
        for (int i = 0; i < n; i++) {
            boolean collapsed = n > CRUMB_TAIL + 1 && i > 0 && i < n - CRUMB_TAIL;
            if (collapsed) {
                if (i == 1) {
                    nodes.add(separator());
                    Label more = new Label("…");
                    more.getStyleClass().add("breadcrumb-ellipsis");
                    more.setMinWidth(USE_PREF_SIZE);
                    nodes.add(more);
                }
                continue;
            }
            if (i > 0) {
                nodes.add(separator());
            }
            nodes.add(crumbButton(trail.get(i), i == n - 1));
        }
        crumbs.getChildren().setAll(nodes);
    }

    private static Node separator() {
        Label sep = new Label("›");
        sep.getStyleClass().add("breadcrumb-separator");
        sep.setMinWidth(USE_PREF_SIZE);
        return sep;
    }

    private static Node crumbButton(Crumb crumb, boolean current) {
        Button b = new Button(crumb.label());
        b.getStyleClass().add("breadcrumb-button");
        b.setFocusTraversable(false);
        b.setMnemonicParsing(false);
        // Earlier crumbs keep their width; the current one is the one that gets ellipsized on a narrow window.
        b.setMinWidth(current ? 40 : USE_PREF_SIZE);
        if (crumb.tooltip() != null) {
            b.setTooltip(new Tooltip(crumb.tooltip()));
        }
        if (current || crumb.action() == null) {
            b.getStyleClass().add("current");
        } else {
            b.setOnAction(e -> crumb.action().run());
        }
        return b;
    }

    /**
     * The action bar — navigation buttons, breadcrumb trail and the toolbar row — leaves the layout in focus mode
     * ({@code visible = false} with {@code managed = false}, so it gives up its space instead of holding an empty
     * gap). What stays is exactly the header contract: the engine picker, the input and the trailing focus switch,
     * with the input growing into the freed width. Idempotent, and it never reparents a node, so the input keeps
     * keyboard focus, its caret and its selection across a toggle.
     */
    public void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        for (Node node : List.of(nav, crumbs)) {
            node.setVisible(visible);
            node.setManaged(visible);
        }
        getStyleClass().remove(FOCUS_STYLE_CLASS);
        if (!visible) {
            getStyleClass().add(FOCUS_STYLE_CLASS);
        }
        field.setMaxWidth(visible ? FIELD_MAX_WIDTH : Double.MAX_VALUE);
        HBox.setHgrow(field, visible ? Priority.NEVER : Priority.ALWAYS);
        syncToolbar();
    }

    private void syncToolbar() {
        boolean want = chromeVisible && !toolbar.getChildren().isEmpty();
        boolean mounted = getChildren().contains(toolbar);
        if (want && !mounted) {
            getChildren().add(toolbar);
        } else if (!want && mounted) {
            getChildren().remove(toolbar);
        }
    }

    public void setInputDisabled(boolean disabled) {
        field.setDisable(disabled);
        toolbar.setDisable(disabled);
    }

    public TextField field() {
        return field;
    }
}
