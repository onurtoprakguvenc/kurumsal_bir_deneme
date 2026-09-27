package org.example.repl;

import org.example.state.Subscription;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Headless command interpreter behind the workbench's bottom terminal panel.
 *
 * <p>The engine knows nothing about indexes or windows: commands are registered as {@link CommandSpec}s by the
 * orchestrator, and the engine only parses, dispatches and records output. That keeps it runnable from a plain
 * console runner and trivially testable.</p>
 *
 * <h2>Output</h2>
 * Output goes to a bounded {@link LineBuffer} — a ring of complete lines with a fixed capacity and a per-line length
 * cap — instead of an ever-growing {@code String}/{@code TextArea} document. Memory is therefore bounded no matter how
 * long the session runs, and a UI can render incrementally: every appended line is streamed to
 * {@link OutputListener}s with a sequence number, so a view appends only what is new (or re-reads a snapshot after
 * {@link OutputListener#cleared()}).
 *
 * <h2>Execution</h2>
 * {@link #execute(String)} runs synchronously (console runners, tests). {@link #submit(String)} runs on a single
 * virtual thread so long commands such as {@code index --rebuild} never block the UI thread, while commands still
 * execute strictly one after another in submission order.
 *
 * <h2>Syntax</h2>
 * {@code name [positional…] [--flag] [--option value] [--option=value] [-- literal…]}; single or double quotes group
 * words (backslashes are literal, so Windows paths need no escaping).
 */
public final class InternalTerminalEngine implements AutoCloseable {

    // ================================================================== output buffer

    /** Receives output as it is produced. Called on the executing thread. */
    public interface OutputListener {
        void line(long sequence, String text);

        default void cleared() {
        }
    }

    /**
     * Fixed-capacity ring of output lines. Appends overwrite the oldest line once full; sequence numbers keep
     * increasing so consumers can tell exactly which lines they have missed.
     */
    public static final class LineBuffer {

        private final String[] ring;
        private final int maxLineChars;
        private final CopyOnWriteArrayList<OutputListener> listeners = new CopyOnWriteArrayList<>();
        private long nextSequence;
        private int size;

        public LineBuffer(int capacity, int maxLineChars) {
            if (capacity < 1 || maxLineChars < 16) {
                throw new IllegalArgumentException("capacity must be >= 1 and maxLineChars >= 16");
            }
            this.ring = new String[capacity];
            this.maxLineChars = maxLineChars;
        }

        /** Appends text, splitting on line breaks; overlong lines are cut with an ellipsis. */
        public void append(CharSequence text) {
            int start = 0;
            int length = text.length();
            for (int i = 0; i <= length; i++) {
                if (i == length || text.charAt(i) == '\n') {
                    int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i;
                    appendLine(text, start, end);
                    start = i + 1;
                }
            }
        }

        private void appendLine(CharSequence text, int from, int to) {
            String line = to - from > maxLineChars
                    ? text.subSequence(from, from + maxLineChars - 1) + "…"
                    : text.subSequence(from, to).toString();
            long sequence;
            synchronized (this) {
                sequence = nextSequence++;
                ring[(int) (sequence % ring.length)] = line;
                size = Math.min(size + 1, ring.length);
            }
            for (OutputListener l : listeners) {
                l.line(sequence, line);
            }
        }

        public void clear() {
            synchronized (this) {
                Arrays.fill(ring, null);
                size = 0;
            }
            for (OutputListener l : listeners) {
                l.cleared();
            }
        }

        public synchronized int size() {
            return size;
        }

        public int capacity() {
            return ring.length;
        }

        /** Sequence number the next line will get; also the total number of lines ever written. */
        public synchronized long nextSequence() {
            return nextSequence;
        }

        /** Retained lines, oldest first. */
        public synchronized List<String> snapshot() {
            return tail(size);
        }

        /** Up to {@code n} most recent lines, oldest first. */
        public synchronized List<String> tail(int n) {
            int count = Math.max(0, Math.min(n, size));
            List<String> out = new ArrayList<>(count);
            for (long s = nextSequence - count; s < nextSequence; s++) {
                out.add(ring[(int) (s % ring.length)]);
            }
            return out;
        }

        public Subscription subscribe(OutputListener listener) {
            listeners.add(Objects.requireNonNull(listener));
            return () -> listeners.remove(listener);
        }
    }

    /** Output port handed to command handlers. */
    public interface Output {
        /** Discards everything; for callers (headless runners, background jobs) that only want the side effects. */
        Output NONE = new Output() {
            @Override
            public void println(CharSequence line) {
            }

            @Override
            public StringBuilder scratch() {
                return new StringBuilder();
            }
        };

        void println(CharSequence line);

        default void println() {
            println("");
        }

        default void printf(String format, Object... args) {
            println(String.format(Locale.ROOT, format, args));
        }

        /** A cleared, reusable builder for composing multi-part lines without string concatenation. */
        StringBuilder scratch();

        default void error(CharSequence message) {
            println("error: " + message);
        }
    }

    // ================================================================== commands

    /**
     * A parsed command line.
     *
     * @param options {@code --name value} / {@code --name=value} pairs
     * @param flags   {@code --name} switches without a value
     */
    public record Invocation(String name, List<String> args, Map<String, String> options, Set<String> flags,
                             String raw) {
        public Invocation {
            args = List.copyOf(args);
            options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
            flags = Collections.unmodifiableSet(new LinkedHashSet<>(flags));
        }

        public boolean has(String flag) {
            return flags.contains(flag) || options.containsKey(flag);
        }

        public String option(String name, String fallback) {
            return options.getOrDefault(name, fallback);
        }

        public int intOption(String name, int fallback, int min, int max) {
            String value = options.get(name);
            if (value == null) {
                return fallback;
            }
            try {
                return Math.max(min, Math.min(max, Integer.parseInt(value.strip())));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--" + name + " expects a number, got '" + value + "'");
            }
        }

        /** Positional arguments joined by single spaces (e.g. a free-text query). */
        public String joinedArgs() {
            return String.join(" ", args);
        }
    }

    @FunctionalInterface
    public interface Handler {
        void run(Invocation invocation, Output out) throws Exception;
    }

    /**
     * @param valueOptions option names that consume the following token as their value (others are flags)
     */
    public record CommandSpec(String name, String usage, String summary, Set<String> valueOptions, Handler handler) {
        public CommandSpec {
            Objects.requireNonNull(name);
            Objects.requireNonNull(handler);
            name = name.toLowerCase(Locale.ROOT);
            valueOptions = Set.copyOf(valueOptions);
            usage = usage == null ? name : usage;
            summary = summary == null ? "" : summary;
        }
    }

    /** Result of one command line. */
    public sealed interface Outcome permits Outcome.Completed, Outcome.Failed, Outcome.Unknown, Outcome.Blank {

        String line();

        record Completed(String line, long nanos) implements Outcome {
        }

        record Failed(String line, String message) implements Outcome {
        }

        record Unknown(String line, String name, String suggestion) implements Outcome {
        }

        record Blank(String line) implements Outcome {
        }

        default boolean succeeded() {
            return this instanceof Completed || this instanceof Blank;
        }
    }

    private static final int HISTORY_LIMIT = 200;

    private final LineBuffer buffer;
    private final Map<String, CommandSpec> commands = new TreeMap<>();
    private final Map<String, String> aliases = new HashMap<>();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final Map<String, Integer> sensitive = new java.util.concurrent.ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Outcome>> outcomeListeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock executionLock = new ReentrantLock();
    private final StringBuilder scratch = new StringBuilder(256);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("dwb-repl").factory());
    private final Output output = new Output() {
        @Override
        public void println(CharSequence line) {
            buffer.append(line);
        }

        @Override
        public StringBuilder scratch() {
            scratch.setLength(0);
            return scratch;
        }
    };

    public InternalTerminalEngine() {
        this(new LineBuffer(2_000, 2_000));
    }

    public InternalTerminalEngine(LineBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer must not be null");
        registerBuiltins();
    }

    public LineBuffer buffer() {
        return buffer;
    }

    public Output output() {
        return output;
    }

    public void register(CommandSpec spec) {
        synchronized (commands) {
            commands.put(spec.name(), spec);
        }
    }

    public void alias(String alias, String target) {
        synchronized (commands) {
            aliases.put(alias.toLowerCase(Locale.ROOT), target.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Marks a command as carrying secrets: only its name and the first {@code visibleArgs} arguments are echoed,
     * kept in history or reported to outcome listeners; the rest is shown as {@code ***}.
     */
    public void markSensitive(String name, int visibleArgs) {
        sensitive.put(name.toLowerCase(Locale.ROOT), Math.max(0, visibleArgs));
    }

    private String resolve(String name) {
        synchronized (commands) {
            return aliases.getOrDefault(name, name);
        }
    }

    private String redact(String specName, List<String> tokens, String raw) {
        Integer visible = sensitive.get(specName);
        if (visible == null || tokens.size() <= 1 + visible) {
            return raw;
        }
        return String.join(" ", tokens.subList(0, 1 + visible)) + " ***";
    }

    public Collection<CommandSpec> commands() {
        synchronized (commands) {
            return List.copyOf(commands.values());
        }
    }

    public List<String> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    // ================================================================== execution

    /** Runs a command line on a background virtual thread; commands execute one at a time in order. */
    public CompletableFuture<Outcome> submit(String line) {
        try {
            return CompletableFuture.supplyAsync(() -> execute(line), executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(new Outcome.Failed(line, "terminal is shut down"));
        }
    }

    /** Observes every finished command line (for auditing); called on the executing thread. */
    public Subscription onOutcome(Consumer<Outcome> listener) {
        outcomeListeners.add(Objects.requireNonNull(listener));
        return () -> outcomeListeners.remove(listener);
    }

    /** Runs a command line on the calling thread. Never throws; failures are reported and returned. */
    public Outcome execute(String line) {
        Outcome outcome = run(line);
        for (Consumer<Outcome> l : outcomeListeners) {
            try {
                l.accept(outcome);
            } catch (RuntimeException ignored) {
                // an auditing listener must never break the terminal
            }
        }
        return outcome;
    }

    private Outcome run(String line) {
        String raw = line == null ? "" : line.strip();
        if (raw.isEmpty()) {
            return new Outcome.Blank(raw);
        }
        executionLock.lock();
        try {
            List<String> tokens;
            try {
                tokens = tokenize(raw);
            } catch (IllegalArgumentException e) {
                String first = raw.split("\\s+", 2)[0];
                String shown = sensitive.containsKey(resolve(first.toLowerCase(Locale.ROOT))) ? first + " ***" : raw;
                remember(shown);
                output.println("> " + shown);
                output.error(e.getMessage());
                return new Outcome.Failed(shown, e.getMessage());
            }
            String name = tokens.getFirst().toLowerCase(Locale.ROOT);
            CommandSpec spec;
            synchronized (commands) {
                spec = commands.get(resolve(name));
            }
            String shown = spec == null ? raw : redact(spec.name(), tokens, raw);
            remember(shown);
            if (spec == null) {
                String suggestion = suggest(name);
                output.println("> " + shown);
                output.error("unknown command '" + name + "'"
                        + (suggestion == null ? "" : " — did you mean '" + suggestion + "'?") + "  (try: help)");
                return new Outcome.Unknown(shown, name, suggestion);
            }
            if (!spec.name().equals("clear")) {
                output.println("> " + shown);
            }
            long started = System.nanoTime();
            try {
                spec.handler().run(parse(spec, tokens, raw), output);
                return new Outcome.Completed(shown, System.nanoTime() - started);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                output.error("interrupted");
                return new Outcome.Failed(shown, "interrupted");
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                output.error(message);
                return new Outcome.Failed(shown, message);
            } catch (OutOfMemoryError e) {
                output.error("out of memory while running '" + spec.name() + "'");
                return new Outcome.Failed(shown, "out of memory");
            }
        } finally {
            executionLock.unlock();
        }
    }

    private void remember(String raw) {
        synchronized (history) {
            if (!raw.equals(history.peekLast())) {
                history.addLast(raw);
                if (history.size() > HISTORY_LIMIT) {
                    history.removeFirst();
                }
            }
        }
    }

    /** Splits on whitespace; quotes group, backslashes are literal. */
    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("unterminated quote");
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static Invocation parse(CommandSpec spec, List<String> tokens, String raw) {
        List<String> args = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        boolean literal = false;
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (literal || !t.startsWith("--")) {
                args.add(t);
                continue;
            }
            if (t.length() == 2) {
                literal = true;
                continue;
            }
            String body = t.substring(2).toLowerCase(Locale.ROOT);
            int eq = body.indexOf('=');
            if (eq > 0) {
                options.put(body.substring(0, eq), t.substring(2 + eq + 1));
            } else if (spec.valueOptions().contains(body) && i + 1 < tokens.size()) {
                options.put(body, tokens.get(++i));
            } else {
                flags.add(body);
            }
        }
        return new Invocation(spec.name(), args, options, flags, raw);
    }

    private String suggest(String name) {
        String best = null;
        int bestDistance = 3;
        synchronized (commands) {
            for (String candidate : commands.keySet()) {
                int d = distance(name, candidate);
                if (d < bestDistance || (name.length() >= 2 && candidate.startsWith(name) && d <= bestDistance)) {
                    bestDistance = d;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private void registerBuiltins() {
        register(new CommandSpec("help", "help [command]", "List commands or show one command's usage", Set.of(),
                (inv, out) -> {
                    if (!inv.args().isEmpty()) {
                        String name = inv.args().getFirst().toLowerCase(Locale.ROOT);
                        CommandSpec spec;
                        synchronized (commands) {
                            spec = commands.get(aliases.getOrDefault(name, name));
                        }
                        if (spec == null) {
                            throw new IllegalArgumentException("no such command: " + name);
                        }
                        out.println("usage: " + spec.usage());
                        out.println("  " + spec.summary());
                        return;
                    }
                    for (CommandSpec spec : commands()) {
                        StringBuilder sb = out.scratch().append("  ").append(spec.usage());
                        while (sb.length() < 44) {
                            sb.append(' ');
                        }
                        out.println(sb.append(' ').append(spec.summary()));
                    }
                }));
        register(new CommandSpec("clear", "clear", "Clear the terminal output", Set.of(),
                (inv, out) -> buffer.clear()));
        register(new CommandSpec("history", "history [--top N]", "Show recent commands", Set.of("top"),
                (inv, out) -> {
                    List<String> all = history();
                    int n = inv.intOption("top", 20, 1, HISTORY_LIMIT);
                    for (int i = Math.max(0, all.size() - n); i < all.size(); i++) {
                        out.printf("%4d  %s", i + 1, all.get(i));
                    }
                }));
        alias("cls", "clear");
        alias("?", "help");
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
