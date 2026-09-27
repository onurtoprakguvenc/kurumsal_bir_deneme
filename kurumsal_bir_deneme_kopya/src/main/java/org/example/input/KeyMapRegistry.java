package org.example.input;

import org.example.state.Subscription;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Central, hot-swappable keyboard shortcut table.
 *
 * <p>Actions are platform-independent ({@link Action}); strokes are toolkit-independent ({@link KeyStroke}, whose
 * key names are the constant names of {@code javafx.scene.input.KeyCode}, so the UI converts with
 * {@code KeyCode.valueOf(stroke.key())} and dispatches with {@link #actionFor(KeyStroke)}). The table is an
 * immutable snapshot behind a volatile reference: lookups on every key event are lock-free, and rebinding swaps the
 * whole snapshot atomically, so a key event can never observe a half-applied change.</p>
 *
 * <p>Bindings persist in a flat, human-editable {@code keybindings.properties}:</p>
 * <pre>
 * FOCUS_SEARCH = Ctrl+F
 * TOGGLE_TERMINAL = Ctrl+J
 * OPEN_SELECTED = none          # explicitly unbound
 * </pre>
 * <p>Loading is forgiving: unknown actions, unparsable strokes and strokes that would steal plain typing are reported
 * as {@link Issue}s and fall back to the default. When two actions claim the same stroke the first in {@link Action}
 * order keeps it and the other falls back to its default if that is free, otherwise it is left unbound — the table
 * never contains a conflict.</p>
 */
public final class KeyMapRegistry {

    /** Workbench commands that can be bound to a key. Declaration order is the conflict-resolution priority. */
    public enum Action {
        FOCUS_SEARCH("Ctrl+F", "Move focus to the search bar"),
        TOGGLE_TERMINAL("Ctrl+J", "Show or hide the terminal panel"),
        OPEN_SELECTED("Enter", "Open the selected document with its default application"),
        SHOW_IN_EXPLORER("Ctrl+Shift+E", "Show the selected document in the file manager"),
        TOGGLE_MODE("Ctrl+M", "Switch between simple and detailed result views"),
        TOGGLE_FILE_TREE("Ctrl+B", "Show or hide the file tree"),
        SAVE_INDEX("Ctrl+S", "Write the index snapshot to disk"),
        CLEAR_TERMINAL("Ctrl+L", "Clear the terminal output"),
        TOGGLE_FOCUS("Ctrl+Shift+F", "Enter or leave distraction-free focus mode"),
        PREVIEW_SELECTED("F3", "Preview the selected document inline"),
        ADD_FILES("Ctrl+O", "Open the file chooser to import and index documents");

        private final String defaultSpec;
        private final String description;

        Action(String defaultSpec, String description) {
            this.defaultSpec = defaultSpec;
            this.description = description;
        }

        public KeyStroke defaultStroke() {
            return KeyStroke.parse(defaultSpec, false).orElseThrow();
        }

        public String description() {
            return description;
        }

        public static Optional<Action> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String key = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
            for (Action a : values()) {
                if (a.name().equals(key)) {
                    return Optional.of(a);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * A key plus modifiers.
     *
     * @param modifiers bitmask of {@link #CTRL}, {@link #ALT}, {@link #SHIFT}, {@link #META}
     * @param key       {@code javafx.scene.input.KeyCode} constant name, e.g. {@code F}, {@code F5}, {@code ENTER}
     */
    public record KeyStroke(int modifiers, String key) {

        public static final int CTRL = 1;
        public static final int ALT = 2;
        public static final int SHIFT = 4;
        public static final int META = 8;

        private static final Pattern FUNCTION_KEY = Pattern.compile("F([1-9]|1[0-9]|2[0-4])");
        private static final Set<String> NAVIGATION = Set.of("ENTER", "ESCAPE", "DELETE", "INSERT", "HOME", "END",
                "PAGE_UP", "PAGE_DOWN", "UP", "DOWN", "LEFT", "RIGHT", "BACK_SPACE", "TAB");
        private static final Set<String> PUNCTUATION = Set.of("SPACE", "SLASH", "BACK_SLASH", "COMMA", "PERIOD",
                "SEMICOLON", "MINUS", "EQUALS", "PLUS", "BACK_QUOTE", "QUOTE", "OPEN_BRACKET", "CLOSE_BRACKET");
        private static final Map<String, String> ALIASES = Map.ofEntries(
                Map.entry("ESC", "ESCAPE"), Map.entry("RETURN", "ENTER"), Map.entry("DEL", "DELETE"),
                Map.entry("INS", "INSERT"), Map.entry("PGUP", "PAGE_UP"), Map.entry("PGDN", "PAGE_DOWN"),
                Map.entry("PAGEUP", "PAGE_UP"), Map.entry("PAGEDOWN", "PAGE_DOWN"), Map.entry("BACKSPACE", "BACK_SPACE"),
                Map.entry("BACKSLASH", "BACK_SLASH"), Map.entry("BACKQUOTE", "BACK_QUOTE"), Map.entry("`", "BACK_QUOTE"),
                Map.entry("/", "SLASH"), Map.entry("\\", "BACK_SLASH"), Map.entry(",", "COMMA"), Map.entry(".", "PERIOD"),
                Map.entry(";", "SEMICOLON"), Map.entry("-", "MINUS"), Map.entry("=", "EQUALS"), Map.entry("'", "QUOTE"),
                Map.entry("+", "PLUS"), Map.entry("[", "OPEN_BRACKET"), Map.entry("]", "CLOSE_BRACKET"), Map.entry("ARROWUP", "UP"),
                Map.entry("ARROWDOWN", "DOWN"), Map.entry("ARROWLEFT", "LEFT"), Map.entry("ARROWRIGHT", "RIGHT"));
        private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

        public KeyStroke {
            if ((modifiers & ~(CTRL | ALT | SHIFT | META)) != 0) {
                throw new IllegalArgumentException("unknown modifier bits");
            }
            key = Objects.requireNonNull(key, "key must not be null");
            if (!isKnownKey(key)) {
                throw new IllegalArgumentException("unknown key " + key);
            }
        }

        public static KeyStroke of(int modifiers, String key) {
            return new KeyStroke(modifiers, key);
        }

        /**
         * Parses {@code Ctrl+Shift+E}, {@code shortcut+s}, {@code F5}, {@code Alt+Page Up}… case-insensitively.
         * {@code Shortcut} means Meta on macOS and Ctrl elsewhere.
         *
         * @param requireSafe when true, strokes that would swallow plain typing (a bare or Shift-only letter,
         *                    digit, punctuation, Space or Tab) are rejected
         */
        public static Optional<KeyStroke> parse(String spec, boolean requireSafe) {
            if (spec == null || spec.isBlank()) {
                return Optional.empty();
            }
            String[] parts = spec.strip().split("\\s*\\+\\s*(?=.)");
            int mods = 0;
            for (int i = 0; i < parts.length - 1; i++) {
                int bit = switch (parts[i].strip().toLowerCase(Locale.ROOT)) {
                    case "ctrl", "control", "ctl" -> CTRL;
                    case "alt", "option", "opt" -> ALT;
                    case "shift" -> SHIFT;
                    case "meta", "cmd", "command", "win", "super" -> META;
                    case "shortcut", "mod" -> MAC ? META : CTRL;
                    default -> -1;
                };
                if (bit < 0 || (mods & bit) != 0) {
                    return Optional.empty();
                }
                mods |= bit;
            }
            String key = canonicalKey(parts[parts.length - 1]);
            if (key == null) {
                return Optional.empty();
            }
            KeyStroke stroke = new KeyStroke(mods, key);
            return requireSafe && !stroke.safe() ? Optional.empty() : Optional.of(stroke);
        }

        /** True unless the stroke would intercept ordinary text entry. */
        public boolean safe() {
            boolean printable = key.length() == 1 || PUNCTUATION.contains(key) || key.equals("TAB");
            return !printable || (modifiers & (CTRL | ALT | META)) != 0;
        }

        private static String canonicalKey(String raw) {
            String k = raw.strip();
            if (k.isEmpty()) {
                return null;
            }
            String upper = k.toUpperCase(Locale.ROOT);
            String compact = upper.replace(" ", "").replace("_", "");
            if (ALIASES.containsKey(upper)) {
                return ALIASES.get(upper);
            }
            if (ALIASES.containsKey(compact)) {
                return ALIASES.get(compact);
            }
            String underscored = upper.replace(' ', '_');
            return isKnownKey(underscored) ? underscored : null;
        }

        private static boolean isKnownKey(String key) {
            if (key.length() == 1) {
                char c = key.charAt(0);
                return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            }
            return FUNCTION_KEY.matcher(key).matches() || NAVIGATION.contains(key) || PUNCTUATION.contains(key);
        }

        /** Canonical human-readable form, e.g. {@code Ctrl+Shift+E}, {@code Alt+Page_Up}; round-trips through parse. */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if ((modifiers & CTRL) != 0) {
                sb.append("Ctrl+");
            }
            if ((modifiers & ALT) != 0) {
                sb.append("Alt+");
            }
            if ((modifiers & SHIFT) != 0) {
                sb.append("Shift+");
            }
            if ((modifiers & META) != 0) {
                sb.append("Meta+");
            }
            if (key.length() == 1 || FUNCTION_KEY.matcher(key).matches()) {
                return sb.append(key).toString();
            }
            String[] words = key.split("_");
            for (int i = 0; i < words.length; i++) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(words[i].charAt(0)).append(words[i].substring(1).toLowerCase(Locale.ROOT));
            }
            return sb.toString();
        }
    }

    /** A problem found while loading or binding; never fatal. */
    public record Issue(String entry, String value, String problem) {
        @Override
        public String toString() {
            return entry + " = " + value + ": " + problem;
        }
    }

    /** Outcome of {@link #bind}. */
    public sealed interface BindResult permits BindResult.Bound, BindResult.Conflict, BindResult.Invalid {

        /** @param displaced action that lost the stroke because of {@code force}, if any */
        record Bound(Action action, KeyStroke stroke, Optional<Action> displaced) implements BindResult {
        }

        record Conflict(Action action, KeyStroke stroke, Action holder) implements BindResult {
        }

        record Invalid(Action action, String spec, String reason) implements BindResult {
        }
    }

    @FunctionalInterface
    public interface ChangeListener {
        void bindingsChanged(Map<Action, KeyStroke> bindings);
    }

    /** Immutable table; both directions always agree. */
    private record Table(Map<Action, KeyStroke> byAction, Map<KeyStroke, Action> byStroke) {
        static Table of(EnumMap<Action, KeyStroke> bindings) {
            Map<KeyStroke, Action> reverse = new HashMap<>();
            bindings.forEach((a, s) -> reverse.put(s, a));
            return new Table(Collections.unmodifiableMap(new EnumMap<>(bindings)), Map.copyOf(reverse));
        }
    }

    private static final String NONE = "none";

    private final Path file;
    private final CopyOnWriteArrayList<ChangeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Table table;
    private volatile long loadedModified = Long.MIN_VALUE;

    public KeyMapRegistry(Path file) {
        this.file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath().normalize();
        this.table = Table.of(defaults());
    }

    public Path file() {
        return file;
    }

    public static EnumMap<Action, KeyStroke> defaults() {
        EnumMap<Action, KeyStroke> map = new EnumMap<>(Action.class);
        for (Action a : Action.values()) {
            map.put(a, a.defaultStroke());
        }
        return map;
    }

    // ================================================================== lookups (lock-free)

    public Optional<Action> actionFor(KeyStroke stroke) {
        return stroke == null ? Optional.empty() : Optional.ofNullable(table.byStroke().get(stroke));
    }

    public Optional<KeyStroke> strokeFor(Action action) {
        return Optional.ofNullable(table.byAction().get(action));
    }

    /** Current bindings (unbound actions are absent). */
    public Map<Action, KeyStroke> bindings() {
        return table.byAction();
    }

    public Subscription onChange(ChangeListener listener) {
        listeners.add(Objects.requireNonNull(listener));
        return () -> listeners.remove(listener);
    }

    // ================================================================== mutation

    /**
     * Binds {@code action} to {@code spec}. Without {@code force}, a stroke held by another action is reported as a
     * {@link BindResult.Conflict} and nothing changes; with {@code force} the holder falls back to its default if that
     * is free, otherwise it becomes unbound.
     */
    public synchronized BindResult bind(Action action, String spec, boolean force) {
        Objects.requireNonNull(action, "action must not be null");
        if (spec != null && spec.strip().equalsIgnoreCase(NONE)) {
            unbind(action);
            return new BindResult.Bound(action, null, Optional.empty());
        }
        Optional<KeyStroke> parsed = KeyStroke.parse(spec, false);
        if (parsed.isEmpty()) {
            return new BindResult.Invalid(action, spec, "unrecognized key stroke");
        }
        KeyStroke stroke = parsed.get();
        if (!stroke.safe()) {
            return new BindResult.Invalid(action, spec, "would intercept normal typing; add Ctrl, Alt or Meta");
        }
        Table current = table;
        Action holder = current.byStroke().get(stroke);
        if (holder == action) {
            return new BindResult.Bound(action, stroke, Optional.empty());
        }
        if (holder != null && !force) {
            return new BindResult.Conflict(action, stroke, holder);
        }
        EnumMap<Action, KeyStroke> next = new EnumMap<>(Action.class);
        next.putAll(current.byAction());
        next.put(action, stroke);
        if (holder != null) {
            next.remove(holder);
            KeyStroke fallback = holder.defaultStroke();
            if (!next.containsValue(fallback)) {
                next.put(holder, fallback);
            }
        }
        swap(Table.of(next));
        return new BindResult.Bound(action, stroke, Optional.ofNullable(holder));
    }

    /**
     * Applies a whole shortcut sheet at once (the UI's shortcut manager). {@code ""} or {@code none} unbinds. Only
     * actions whose stroke actually changes are touched, in two passes — unbind all of them, then bind each without
     * force — so swapping two shortcuts works, while a genuine clash is reported as a {@link BindResult.Conflict}
     * instead of silently reassigning another action. An action whose new stroke is refused keeps its previous one
     * when that is still free.
     *
     * @return the result for every changed action that has a new stroke (unbinds are not listed)
     */
    public synchronized List<BindResult> bindAll(Map<Action, String> sheet) {
        Map<Action, KeyStroke> before = table.byAction();
        List<Action> changed = new ArrayList<>();
        sheet.forEach((action, raw) -> {
            String spec = raw == null ? "" : raw.strip();
            String current = strokeFor(action).map(Object::toString).orElse("");
            boolean unbound = spec.isEmpty() || spec.equalsIgnoreCase(NONE);
            if (unbound ? !current.isEmpty() : !current.equalsIgnoreCase(spec)) {
                changed.add(action);
            }
        });
        for (Action a : changed) {
            unbind(a);
        }
        List<BindResult> results = new ArrayList<>();
        for (Action a : changed) {
            String spec = sheet.get(a) == null ? "" : sheet.get(a).strip();
            if (!spec.isEmpty() && !spec.equalsIgnoreCase(NONE)) {
                BindResult r = bind(a, spec, false);
                results.add(r);
                KeyStroke previous = before.get(a);
                if (!(r instanceof BindResult.Bound) && previous != null && actionFor(previous).isEmpty()) {
                    bind(a, previous.toString(), false);
                }
            }
        }
        return results;
    }

    public synchronized void unbind(Action action) {
        EnumMap<Action, KeyStroke> next = new EnumMap<>(Action.class);
        next.putAll(table.byAction());
        if (next.remove(action) != null) {
            swap(Table.of(next));
        }
    }

    public synchronized void resetDefaults() {
        swap(Table.of(defaults()));
    }

    private void swap(Table next) {
        table = next;
        for (ChangeListener l : listeners) {
            l.bindingsChanged(next.byAction());
        }
    }

    // ================================================================== persistence

    /**
     * Loads the file, or writes the defaults when it does not exist yet. Never throws for bad content; problems
     * are returned and the affected actions fall back to their defaults.
     */
    public synchronized List<Issue> load() throws IOException {
        Properties props = new Properties();
        long modified;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            modified = Files.getLastModifiedTime(file).toMillis();
            props.load(reader);
        } catch (NoSuchFileException e) {
            swap(Table.of(defaults()));
            save();
            return List.of();
        }
        List<Issue> issues = new ArrayList<>();
        swap(resolve(props, issues));
        loadedModified = modified;
        return issues;
    }

    /** Hot reload: re-reads the file only if it changed on disk since the last load or save. */
    public synchronized Optional<List<Issue>> reloadIfChanged() throws IOException {
        long modified;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        return modified == loadedModified ? Optional.empty() : Optional.of(load());
    }

    private static Table resolve(Properties props, List<Issue> issues) {
        EnumMap<Action, KeyStroke> requested = new EnumMap<>(Action.class);
        EnumMap<Action, Boolean> explicitNone = new EnumMap<>(Action.class);
        for (String name : props.stringPropertyNames()) {
            String value = props.getProperty(name).strip();
            Optional<Action> action = Action.parse(name);
            if (action.isEmpty()) {
                issues.add(new Issue(name, value, "unknown action (ignored)"));
                continue;
            }
            if (value.equalsIgnoreCase(NONE) || value.isEmpty()) {
                explicitNone.put(action.get(), true);
                continue;
            }
            Optional<KeyStroke> stroke = KeyStroke.parse(value, false);
            if (stroke.isEmpty()) {
                issues.add(new Issue(name, value, "unrecognized key stroke; using default"));
            } else if (!stroke.get().safe()) {
                issues.add(new Issue(name, value, "would intercept normal typing; using default"));
            } else {
                requested.put(action.get(), stroke.get());
            }
        }

        EnumMap<Action, KeyStroke> result = new EnumMap<>(Action.class);
        Map<KeyStroke, Action> taken = new HashMap<>();
        // Pass 1: explicit user choices, in priority order.
        for (Map.Entry<Action, KeyStroke> e : requested.entrySet()) {
            Action holder = taken.get(e.getValue());
            if (holder != null) {
                issues.add(new Issue(e.getKey().name(), e.getValue().toString(),
                        "conflicts with " + holder + "; falling back"));
                continue;
            }
            result.put(e.getKey(), e.getValue());
            taken.put(e.getValue(), e.getKey());
        }
        // Pass 2: defaults for everything not explicitly bound or unbound, when still free.
        for (Action a : Action.values()) {
            if (result.containsKey(a) || explicitNone.containsKey(a)) {
                continue;
            }
            KeyStroke fallback = a.defaultStroke();
            Action holder = taken.get(fallback);
            if (holder != null) {
                issues.add(new Issue(a.name(), fallback.toString(), "default is used by " + holder + "; left unbound"));
                continue;
            }
            result.put(a, fallback);
            taken.put(fallback, a);
        }
        return Table.of(result);
    }

    /** Writes every action (unbound ones as {@code none}) atomically via a temp file. */
    public synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Map<Action, KeyStroke> current = table.byAction();
        try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            w.write("# Document Workbench key bindings\n");
            w.write("# ACTION = [Ctrl+][Alt+][Shift+][Meta+]Key   ('Shortcut' = Ctrl, or Cmd on macOS; 'none' unbinds)\n");
            w.write("# Keys: A-Z, 0-9, F1-F24, Enter, Escape, Delete, Home, End, Page_Up, Page_Down, Up, Down, Left,\n");
            w.write("#       Right, Slash, Comma, Period, Minus, Equals, Back_Quote, ...  Delete a line to restore its default.\n\n");
            for (Action a : Action.values()) {
                KeyStroke s = current.get(a);
                w.write("# " + a.description() + " (default " + a.defaultStroke() + ")\n");
                w.write(a.name() + " = " + (s == null ? NONE : s.toString()) + "\n");
            }
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        loadedModified = Files.getLastModifiedTime(file).toMillis();
    }
}
