package org.example.admin;

import org.example.admin.AuditLog.Category;
import org.example.admin.AuditLog.Level;
import org.example.core.HeapGuard;
import org.example.core.IngestionException;
import org.example.core.SearchIndex;
import org.example.model.DocumentRecord;
import org.example.platform.OsShellBridge.ShellResult;
import org.example.repl.InternalTerminalEngine;
import org.example.repl.InternalTerminalEngine.CommandSpec;
import org.example.repl.InternalTerminalEngine.Invocation;
import org.example.repl.InternalTerminalEngine.Output;
import org.example.storage.MetadataRecord;
import org.example.workbench.WorkbenchController;
import org.example.workbench.WorkbenchController.WorkbenchEvent;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * IT administration: exclusion policy, resource limits, access control, health audits and the audit trail.
 *
 * <h2>Policy</h2>
 * Exclusion patterns are case-insensitive globs. A pattern without a slash ({@code node_modules}, {@code *.tmp},
 * {@code ~$*}) is matched against every path segment, so it excludes a directory and everything below it; a pattern
 * with a slash ({@code **}{@code /build/**}) is matched against the whole path. The engine is installed into a
 * {@link WorkbenchController} as its {@link WorkbenchController.IngestPolicy}: excluded directories are pruned from
 * walks, ingestion is refused while the heap is above the configured ceiling, and bulk adds use the configured
 * number of workers.
 *
 * <h2>Access control</h2>
 * Optional. When a passphrase is set (stored as a salted PBKDF2-HMAC-SHA256 hash in {@code admin.properties}, never
 * in clear text), privileged operations require an {@link #unlock(char[]) unlocked} session, which expires after
 * {@code unlockMinutes}. Five consecutive failures lock unlocking for a minute. Alternatively the environment
 * variable {@code DWB_ADMIN_TOKEN} is accepted as a deployment token (compared in constant time). Every privileged
 * call, success or denial, is written to the audit log.
 *
 * <h2>Limits</h2>
 * {@code -Xmx} cannot change at runtime; the memory ceiling here is the soft admission threshold (fraction of the
 * max heap) above which new ingestion and previews are refused, plus explicit GC triggering for diagnostics.
 */
public final class AdminControlEngine implements AutoCloseable {

    public static final List<String> DEFAULT_EXCLUSIONS = List.of(
            ".git", ".svn", ".hg", "node_modules", "__pycache__", ".idea", ".vscode", "$RECYCLE.BIN",
            "System Volume Information", "~$*", "*.tmp", "*.temp", "*.bak", "*.swp", "*.part", "*.dwb_part", "*.crdownload",
            "Thumbs.db", "desktop.ini");

    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int MIN_PBKDF2_ITERATIONS = 10_000;
    private static final int MAX_PBKDF2_ITERATIONS = 10_000_000;
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(1);

    /**
     * @param exclusions    glob patterns never indexed
     * @param memoryCeiling fraction of max heap above which ingestion is refused (0.50–0.95)
     * @param workers       concurrent extractions during bulk adds (1–8)
     * @param unlockMinutes admin session lifetime
     * @param passHash      {@code pbkdf2$iterations$salt$hash} or {@code null} when access control is off
     */
    public record Settings(List<String> exclusions, double memoryCeiling, int workers, int unlockMinutes,
                           String passHash) {
        public Settings {
            exclusions = List.copyOf(new LinkedHashSet<>(exclusions));
            if (!(memoryCeiling >= 0.5 && memoryCeiling <= 0.95)) {
                throw new IllegalArgumentException("memory ceiling must be between 0.50 and 0.95");
            }
            if (workers < 1 || workers > 8) {
                throw new IllegalArgumentException("workers must be between 1 and 8");
            }
            unlockMinutes = Math.max(1, Math.min(unlockMinutes, 240));
        }

        public static Settings defaults() {
            return new Settings(DEFAULT_EXCLUSIONS, HeapGuard.DEFAULT_CEILING, 1, 10, null);
        }

        Settings withExclusions(List<String> e) {
            return new Settings(e, memoryCeiling, workers, unlockMinutes, passHash);
        }

        Settings withMemoryCeiling(double c) {
            return new Settings(exclusions, c, workers, unlockMinutes, passHash);
        }

        Settings withWorkers(int w) {
            return new Settings(exclusions, memoryCeiling, w, unlockMinutes, passHash);
        }

        Settings withPassHash(String h) {
            return new Settings(exclusions, memoryCeiling, workers, unlockMinutes, h);
        }
    }

    /** Thrown when a privileged operation is attempted without an unlocked session. */
    public static final class PrivilegeException extends Exception {
        public PrivilegeException(String message) {
            super(message);
        }
    }

    public sealed interface UnlockResult permits UnlockResult.Unlocked, UnlockResult.Denied,
            UnlockResult.LockedOut, UnlockResult.NotRequired {
        record Unlocked(Instant until) implements UnlockResult {
        }

        record Denied(int remainingAttempts) implements UnlockResult {
        }

        record LockedOut(Instant until) implements UnlockResult {
        }

        record NotRequired() implements UnlockResult {
        }
    }

    public enum Severity {
        OK, INFO, WARN, CRITICAL
    }

    public record Finding(Severity severity, String code, String message) {
    }

    public record HealthReport(Instant at, List<Finding> findings, Map<String, Long> metrics) {
        public HealthReport {
            findings = List.copyOf(findings);
            metrics = Map.copyOf(metrics);
        }

        public Severity worst() {
            return findings.stream().map(Finding::severity).max(Enum::compareTo).orElse(Severity.OK);
        }
    }

    public record GcReport(long usedBeforeBytes, long usedAfterBytes, long collections, long millis) {
        public long freedBytes() {
            return Math.max(0, usedBeforeBytes - usedAfterBytes);
        }
    }

    private final Path configFile;
    private final AuditLog audit;
    private final Clock clock;
    private final String deploymentToken;
    private final SecureRandom random = new SecureRandom();

    private final CentralPolicy policy;
    /** What {@code admin.properties} holds: the user-editable layer, persisted by {@link #save()}. */
    private volatile Settings local;
    /** {@link #local} with the central policy applied on top: what is actually enforced. */
    private volatile Settings effective;
    private volatile List<Pattern> segmentPatterns = List.of();
    private volatile List<Pattern> pathPatterns = List.of();
    private Instant unlockedUntil = Instant.MIN;
    private Instant lockedOutUntil = Instant.MIN;
    private int failures;

    /**
     * @param deploymentToken optional token accepted by {@link #unlock(char[])} (e.g. {@code DWB_ADMIN_TOKEN})
     */
    public AdminControlEngine(Path configFile, AuditLog audit, Clock clock, String deploymentToken) {
        this(configFile, audit, clock, deploymentToken, CentralPolicy.none());
    }

    /**
     * @param policy machine-wide IT policy; whatever it sets overrides {@code admin.properties} and cannot be changed
     *               from the application
     */
    public AdminControlEngine(Path configFile, AuditLog audit, Clock clock, String deploymentToken,
                              CentralPolicy policy) {
        this.configFile = Objects.requireNonNull(configFile, "configFile must not be null").toAbsolutePath();
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.deploymentToken = deploymentToken == null || deploymentToken.isBlank() ? null : deploymentToken;
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        apply(Settings.defaults());
    }

    public AdminControlEngine(Path dataDir) {
        this(dataDir.resolve("admin.properties"), new AuditLog(dataDir.resolve("audit.log")), Clock.systemUTC(),
                System.getenv("DWB_ADMIN_TOKEN"));
    }

    public AuditLog audit() {
        return audit;
    }

    /** The settings in force: the local settings with the central policy applied on top. */
    public Settings settings() {
        return effective;
    }

    /** The user-editable layer alone ({@code admin.properties}). */
    public Settings localSettings() {
        return local;
    }

    public CentralPolicy policy() {
        return policy;
    }

    /** True when the central policy fixes this exclusion pattern (it cannot be removed here). */
    public boolean centralExclusion(String pattern) {
        return policy.exclusions().stream().anyMatch(e -> e.equalsIgnoreCase(pattern.strip()));
    }

    private void apply(Settings next) {
        List<String> exclusions = new ArrayList<>(policy.exclusions());
        if (!(policy.replacesExclusions() && !policy.exclusions().isEmpty())) {
            exclusions.addAll(next.exclusions());
        }
        Settings merged = new Settings(exclusions, policy.memoryCeiling().orElse(next.memoryCeiling()),
                policy.workers().orElse(next.workers()), policy.unlockMinutes().orElse(next.unlockMinutes()),
                next.passHash());
        List<Pattern> segments = new ArrayList<>();
        List<Pattern> paths = new ArrayList<>();
        for (String raw : merged.exclusions()) {
            String p = raw.strip().replace('\\', '/');
            if (p.isEmpty()) {
                continue;
            }
            (p.contains("/") ? paths : segments).add(globToRegex(p));
        }
        segmentPatterns = List.copyOf(segments);
        pathPatterns = List.copyOf(paths);
        local = next;
        effective = merged;
    }

    private void refuseIfCentral(boolean central, String what) throws PrivilegeException {
        if (central) {
            audit.record(Level.SECURITY, Category.ADMIN, "op", what, "result", "refused-central-policy");
            throw new PrivilegeException(what + " is set by the central IT policy (" + policy.file()
                    + ") and cannot be changed here");
        }
    }

    // ================================================================== persistence

    /** Loads {@code admin.properties}, writing defaults on first start. Invalid values fall back to defaults. */
    public synchronized List<String> load() throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (NoSuchFileException e) {
            save();
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        Settings d = Settings.defaults();
        List<String> exclusions = new ArrayList<>();
        for (int i = 1; p.containsKey("exclude." + i); i++) {
            exclusions.add(p.getProperty("exclude." + i));
        }
        double ceiling = parse(p, "memory.ceiling", d.memoryCeiling(), problems, Double::parseDouble);
        int workers = parse(p, "workers", (double) d.workers(), problems, Double::parseDouble).intValue();
        int minutes = parse(p, "unlock.minutes", (double) d.unlockMinutes(), problems, Double::parseDouble).intValue();
        String hash = p.getProperty("passphrase.hash");
        try {
            apply(new Settings(p.containsKey("exclude.1") ? exclusions : d.exclusions(), ceiling, workers, minutes,
                    hash == null || hash.isBlank() ? null : hash));
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage() + "; defaults used");
            apply(d.withPassHash(hash == null || hash.isBlank() ? null : hash));
        }
        problems.addAll(policy.problems());
        if (policy.writableByUser()) {
            problems.add("central policy " + policy.file() + " is writable by this user: its settings are not"
                    + " enforced until Users get read-only access");
        }
        return problems;
    }

    private static Double parse(Properties p, String key, double fallback, List<String> problems,
                                java.util.function.ToDoubleFunction<String> parser) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        try {
            return parser.applyAsDouble(v.strip());
        } catch (NumberFormatException e) {
            problems.add(key + " = " + v + " is not a number");
            return fallback;
        }
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(configFile.getParent());
        Path temp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        Settings s = local;
        try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            w.write("# Document Workbench administration policy (managed by the 'blacklist' / 'limit' commands)\n");
            w.write(String.format(Locale.ROOT, "memory.ceiling = %.2f%n", s.memoryCeiling()));
            w.write("workers = " + s.workers() + "\n");
            w.write("unlock.minutes = " + s.unlockMinutes() + "\n");
            if (s.passHash() != null) {
                w.write("passphrase.hash = " + s.passHash() + "\n");
            }
            int i = 1;
            for (String e : s.exclusions()) {
                w.write("exclude." + (i++) + " = " + escape(e) + "\n");
            }
        }
        try {
            Files.move(temp, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Escapes characters that {@link Properties#load} would interpret. */
    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '#' || c == '!' || c == '=' || c == ':' || (i == 0 && c == ' ')) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ================================================================== exclusion policy

    /** True when any path segment matches a segment pattern or the whole path matches a path pattern. */
    public boolean excluded(Path path) {
        if (path == null) {
            return false;
        }
        return segmentExcluded(path) || pathExcluded(path);
    }

    /**
     * Exclusion of {@code path} found below {@code root}, the folder the user added. Name patterns only look at the
     * segments inside {@code root} (for a path outside it, at its file name), so the folders the project happens to
     * live in — {@code D:\arsiv.bak\…} — never exclude it as a whole; full-path patterns still see the whole path.
     */
    public boolean excluded(Path root, Path path) {
        if (path == null) {
            return false;
        }
        Path inside;
        try {
            Path r = root == null ? null : root.toAbsolutePath().normalize();
            Path p = path.toAbsolutePath().normalize();
            inside = r != null && p.startsWith(r) && !p.equals(r) ? r.relativize(p) : p.getFileName();
        } catch (RuntimeException e) {
            inside = path.getFileName();
        }
        return (inside != null && segmentExcluded(inside)) || pathExcluded(path);
    }

    private boolean segmentExcluded(Path path) {
        for (Path segment : path) {
            String name = segment.toString();
            for (Pattern p : segmentPatterns) {
                if (p.matcher(name).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean pathExcluded(Path path) {
        if (!pathPatterns.isEmpty()) {
            String whole = path.toAbsolutePath().normalize().toString().replace('\\', '/');
            for (Pattern p : pathPatterns) {
                if (p.matcher(whole).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<String> exclusions() {
        return effective.exclusions();
    }

    public synchronized boolean addExclusion(String pattern) throws PrivilegeException, IOException {
        requirePrivilege("blacklist --add " + pattern);
        String p = validPattern(pattern);
        if (effective.exclusions().stream().anyMatch(e -> e.equalsIgnoreCase(p))) {
            return false;
        }
        List<String> next = new ArrayList<>(local.exclusions());
        next.add(p);
        apply(local.withExclusions(next));
        save();
        audit.record(Level.INFO, Category.ADMIN, "op", "exclude.add", "pattern", p);
        return true;
    }

    public synchronized boolean removeExclusion(String pattern) throws PrivilegeException, IOException {
        requirePrivilege("blacklist --remove " + pattern);
        refuseIfCentral(centralExclusion(pattern), "blacklist --remove");
        List<String> next = new ArrayList<>(local.exclusions());
        if (!next.removeIf(e -> e.equalsIgnoreCase(pattern.strip()))) {
            return false;
        }
        apply(local.withExclusions(next));
        save();
        audit.record(Level.INFO, Category.ADMIN, "op", "exclude.remove", "pattern", pattern.strip());
        return true;
    }

    private static String validPattern(String pattern) {
        String p = pattern == null ? "" : pattern.strip();
        if (p.isEmpty() || p.length() > 260 || p.equals("*") || p.equals("**") || p.equals("**/*")) {
            throw new IllegalArgumentException("refusing empty or match-everything pattern '" + p + "'");
        }
        return p;
    }

    /** Case-insensitive glob: {@code **} any path, {@code *} within a segment, {@code ?} one character. */
    static Pattern globToRegex(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                re.append(".*");
                i++;
            } else if (c == '*') {
                re.append("[^/]*");
            } else if (c == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    // ================================================================== limits

    public synchronized void setMemoryCeiling(double ceiling) throws PrivilegeException, IOException {
        requirePrivilege("limit --memory " + ceiling);
        refuseIfCentral(policy.memoryCeiling().isPresent(), "memory ceiling");
        apply(local.withMemoryCeiling(ceiling));
        save();
        audit.record(Level.INFO, Category.ADMIN, "op", "limit.memory", "value", String.valueOf(ceiling));
    }

    public synchronized void setWorkers(int workers) throws PrivilegeException, IOException {
        requirePrivilege("limit --workers " + workers);
        refuseIfCentral(policy.workers().isPresent(), "worker count");
        apply(local.withWorkers(workers));
        save();
        audit.record(Level.INFO, Category.ADMIN, "op", "limit.workers", "value", String.valueOf(workers));
    }

    /** Requests a full collection and reports what it freed. Diagnostic only: the JVM may ignore the request. */
    public GcReport forceGc() throws PrivilegeException {
        requirePrivilege("gc");
        Runtime rt = Runtime.getRuntime();
        long before = rt.totalMemory() - rt.freeMemory();
        long gcBefore = gcCount();
        long t0 = System.nanoTime();
        System.gc();
        long millis = (System.nanoTime() - t0) / 1_000_000;
        long after = rt.totalMemory() - rt.freeMemory();
        GcReport report = new GcReport(before, after, gcCount() - gcBefore, millis);
        audit.record(Level.INFO, Category.ADMIN, "op", "gc", "freedKB", String.valueOf(report.freedBytes() >> 10),
                "ms", String.valueOf(millis));
        return report;
    }

    private static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, gc.getCollectionCount());
        }
        return n;
    }

    /** The policy object to install with {@link WorkbenchController#setIngestPolicy}. */
    public WorkbenchController.IngestPolicy ingestPolicy() {
        return new WorkbenchController.IngestPolicy() {
            @Override
            public boolean excluded(Path path) {
                return AdminControlEngine.this.excluded(path);
            }

            @Override
            public boolean excluded(Path root, Path path) {
                return AdminControlEngine.this.excluded(root, path);
            }

            @Override
            public Optional<IngestionException> refusal(Path file) {
                HeapGuard guard = new HeapGuard(effective.memoryCeiling());
                if (guard.underPressure()) {
                    return Optional.of(new IngestionException(IngestionException.Reason.MEMORY_PRESSURE,
                            "admin memory ceiling reached: " + guard.describe()));
                }
                return Optional.empty();
            }

            @Override
            public int parallelism() {
                return effective.workers();
            }
        };
    }

    // ================================================================== access control

    public boolean protectedMode() {
        return policy.passphraseHash().isPresent() || local.passHash() != null || deploymentToken != null;
    }

    public synchronized boolean unlocked() {
        return !protectedMode() || clock.instant().isBefore(unlockedUntil);
    }

    public synchronized UnlockResult unlock(char[] secret) {
        try {
            if (!protectedMode()) {
                return new UnlockResult.NotRequired();
            }
            Instant now = clock.instant();
            if (now.isBefore(lockedOutUntil)) {
                audit.record(Level.SECURITY, Category.AUTH, "op", "unlock", "result", "locked-out");
                return new UnlockResult.LockedOut(lockedOutUntil);
            }
            // A central passphrase replaces the local one; the deployment token works alongside either.
            String stored = policy.passphraseHash().orElse(local.passHash());
            boolean ok = (stored != null && verify(secret, stored))
                    || (deploymentToken != null && MessageDigest.isEqual(
                    new String(secret).getBytes(StandardCharsets.UTF_8), deploymentToken.getBytes(StandardCharsets.UTF_8)));
            if (ok) {
                failures = 0;
                unlockedUntil = now.plus(Duration.ofMinutes(effective.unlockMinutes()));
                audit.record(Level.SECURITY, Category.AUTH, "op", "unlock", "result", "granted");
                return new UnlockResult.Unlocked(unlockedUntil);
            }
            failures++;
            audit.record(Level.SECURITY, Category.AUTH, "op", "unlock", "result", "denied",
                    "failures", String.valueOf(failures));
            if (failures >= MAX_FAILURES) {
                failures = 0;
                lockedOutUntil = now.plus(LOCKOUT);
                return new UnlockResult.LockedOut(lockedOutUntil);
            }
            return new UnlockResult.Denied(MAX_FAILURES - failures);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    public synchronized void lock() {
        unlockedUntil = Instant.MIN;
    }

    /**
     * Sets or replaces the passphrase ({@code null}/empty removes it). When one is already set the session must be
     * unlocked first.
     */
    public synchronized void setPassphrase(char[] next) throws PrivilegeException, IOException {
        try {
            requirePrivilege("admin passwd");
            refuseIfCentral(policy.passphraseHash().isPresent(), "admin passphrase");
            if (next == null || next.length == 0) {
                apply(local.withPassHash(null));
            } else {
                if (next.length < 8) {
                    throw new IllegalArgumentException("passphrase must have at least 8 characters");
                }
                apply(local.withPassHash(hash(next)));
            }
            save();
            audit.record(Level.SECURITY, Category.ADMIN, "op", "passphrase", "result",
                    local.passHash() == null ? "removed" : "set");
        } finally {
            if (next != null) {
                Arrays.fill(next, '\0');
            }
        }
    }

    /** Throws unless access control is off or the session is unlocked; audits denials. */
    public void requirePrivilege(String operation) throws PrivilegeException {
        if (!unlocked()) {
            audit.record(Level.SECURITY, Category.AUTH, "op", operation, "result", "denied");
            throw new PrivilegeException("'" + operation + "' requires an unlocked admin session (admin unlock)");
        }
    }

    private String hash(char[] secret) {
        return hashPassphrase(secret);
    }

    /**
     * The {@code pbkdf2$…} form stored in {@code admin.properties} and in the central policy's
     * {@code admin.passphrase.hash}; IT creates it with {@code dwb-cli --hash-passphrase} or {@code admin hash}.
     */
    public static String hashPassphrase(char[] secret) {
        if (secret == null || secret.length < 8) {
            throw new IllegalArgumentException("passphrase must have at least 8 characters");
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] derived = pbkdf2(secret, salt, PBKDF2_ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(derived);
    }

    private static boolean verify(char[] secret, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2")) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            // The stored count is read from an editable file: refuse values that would make verification trivially
            // cheap or would stall the terminal thread.
            if (iterations < MIN_PBKDF2_ITERATIONS || iterations > MAX_PBKDF2_ITERATIONS) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(pbkdf2(secret, salt, iterations), expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] secret, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    // ================================================================== health audit

    /** Read-only health audit of the active workspace. Stats every local source once. */
    public HealthReport healthAudit(WorkbenchController controller) {
        List<Finding> findings = new ArrayList<>();
        Map<String, Long> metrics = new HashMap<>();
        SearchIndex.Stats stats = controller.workbench().stats();
        metrics.put("documents", (long) stats.documents());
        metrics.put("chunks", (long) stats.chunks());
        metrics.put("terms", (long) stats.terms());
        metrics.put("postings", stats.postings());

        int changed = 0;
        int missing = 0;
        int excludedIndexed = 0;
        int partialScans = 0;
        Map<String, Integer> names = new HashMap<>();
        for (DocumentRecord d : controller.workbench().documents()) {
            names.merge(d.fileName().toLowerCase(Locale.ROOT), 1, Integer::sum);
            if (d.emptyPages() > 0) {
                partialScans++;
            }
            if (!d.local()) {
                continue;
            }
            if (excluded(d.source())) {
                excludedIndexed++;
            }
            switch (controller.metadata(d).freshness()) {
                case CHANGED -> changed++;
                case MISSING -> missing++;
                case FRESH, UNKNOWN -> {
                }
            }
        }
        metrics.put("changedSources", (long) changed);
        metrics.put("missingSources", (long) missing);
        if (stats.documents() == 0) {
            findings.add(new Finding(Severity.INFO, "EMPTY_INDEX", "no documents indexed"));
        }
        if (missing > 0) {
            findings.add(new Finding(Severity.WARN, "MISSING_SOURCES", missing
                    + " indexed document(s) no longer exist on disk; run 'index --rebuild'"));
        }
        if (changed > 0) {
            findings.add(new Finding(Severity.WARN, "STALE_SOURCES", changed
                    + " document(s) changed since indexing; run 'index --rebuild'"));
        }
        if (excludedIndexed > 0) {
            findings.add(new Finding(Severity.WARN, "EXCLUDED_BUT_INDEXED", excludedIndexed
                    + " indexed document(s) match the current exclusion list"));
        }
        if (partialScans > 0) {
            findings.add(new Finding(Severity.INFO, "SCANNED_PAGES", partialScans
                    + " PDF(s) contain pages without a text layer (not searchable without OCR)"));
        }
        long forks = names.values().stream().filter(n -> n > 1).count();
        if (forks > 0) {
            findings.add(new Finding(Severity.INFO, "NAME_COLLISIONS", forks + " file name(s) exist in several versions"));
        }

        double usage = HeapGuard.usageRatio();
        metrics.put("heapUsedPercent", Math.round(usage * 100));
        metrics.put("heapMaxMB", Runtime.getRuntime().maxMemory() >> 20);
        if (usage > effective.memoryCeiling()) {
            findings.add(new Finding(Severity.CRITICAL, "HEAP_ABOVE_CEILING", String.format(Locale.ROOT,
                    "heap at %.0f%% is above the %.0f%% ceiling; ingestion is refused", usage * 100,
                    effective.memoryCeiling() * 100)));
        } else if (usage > effective.memoryCeiling() * 0.9) {
            findings.add(new Finding(Severity.WARN, "HEAP_NEAR_CEILING",
                    String.format(Locale.ROOT, "heap at %.0f%%, close to the ceiling", usage * 100)));
        }
        if (controller.dirty()) {
            findings.add(new Finding(Severity.INFO, "UNSAVED_CHANGES", "index differs from its snapshot"));
        }
        if (!Files.isRegularFile(controller.storage().file()) && stats.documents() > 0) {
            findings.add(new Finding(Severity.WARN, "NO_SNAPSHOT", "index has never been saved; a crash loses it"));
        }
        if (audit.lastError() != null) {
            findings.add(new Finding(Severity.CRITICAL, "AUDIT_UNWRITABLE",
                    "audit log cannot be written: " + audit.lastError().getMessage()));
        }
        if (findings.isEmpty()) {
            findings.add(new Finding(Severity.OK, "HEALTHY", "no issues found"));
        }
        return new HealthReport(clock.instant(), findings, metrics);
    }

    // ================================================================== audit trail wiring

    /** Listener that turns controller events into audit entries; subscribe it with {@code controller.onEvent}. */
    public Consumer<WorkbenchEvent> eventRecorder() {
        return event -> {
            switch (event) {
                case WorkbenchEvent.IngestFailed(Path file, IngestionException.Reason reason, String message) ->
                        audit.record(Level.WARN, Category.INGEST_FAILURE, "file", String.valueOf(file), "reason",
                                reason.name(), "msg", message);
                case WorkbenchEvent.IngestRefused(Path file, String reason) ->
                        audit.record(Level.INFO, Category.INGEST_REFUSED, "file", String.valueOf(file), "reason",
                                reason);
                case WorkbenchEvent.FileAction(ShellResult result) ->
                        audit.record(result.ok() ? Level.INFO : Level.WARN, Category.FILE_ACCESS, "action",
                                result.action().name(), "file", String.valueOf(result.path()), "result",
                                result.getClass().getSimpleName(), "detail", result.describe());
                case WorkbenchEvent.CommandFailed(String line, String message) ->
                        audit.record(Level.ERROR, Category.RUNTIME_ERROR, "command", line, "msg", message);
                case WorkbenchEvent.Ingested ingested -> {
                    // Successful ingestion is routine; the snapshot manifest already records it.
                }
                case WorkbenchEvent.AssetRegistered registered -> {
                    // Routine as well: the content store's asset list records it.
                }
                case WorkbenchEvent.Removed(DocumentRecord document, boolean fromDisk) ->
                        audit.record(fromDisk ? Level.WARN : Level.INFO, Category.FILE_ACCESS, "action",
                                fromDisk ? "DELETE" : "UNTRACK", "file", String.valueOf(document.source()), "doc",
                                document.shortId());
            }
        };
    }

    // ================================================================== terminal commands

    /** Registers {@code admin}, {@code audit}, {@code blacklist}, {@code limit} and {@code gc}. */
    public void registerCommands(InternalTerminalEngine terminal, Supplier<WorkbenchController> controller) {
        terminal.register(new CommandSpec("admin", "admin unlock <secret> | lock | status | passwd <new|--clear> | hash <secret>",
                "Admin session and passphrase", Set.of(), this::cmdAdmin));
        terminal.register(new CommandSpec("audit",
                "audit [--health] [--tail N] [--export --since yyyy-mm-dd [--out FILE]]",
                "Index health audit, the last audit.log entries, or an export for a compliance report (privileged)",
                Set.of("tail", "since", "out"),
                (inv, out) -> cmdAudit(inv, out, controller.get())));
        terminal.register(new CommandSpec("blacklist", "blacklist [--list] [--add <glob>] [--remove <glob>] [--test <path>]",
                "Exclusion patterns never indexed", Set.of("add", "remove", "test"), this::cmdBlacklist));
        terminal.register(new CommandSpec("limit", "limit [--memory 0.50-0.95] [--workers 1-8]",
                "Show or set the memory ceiling and bulk worker count", Set.of("memory", "workers"), this::cmdLimit));
        terminal.register(new CommandSpec("gc", "gc", "Force a garbage collection and report freed heap (privileged)",
                Set.of(), (inv, out) -> {
                    GcReport r = forceGc();
                    out.printf("gc: %d MB -> %d MB (freed %d MB, %d collection(s), %d ms)", r.usedBeforeBytes() >> 20,
                            r.usedAfterBytes() >> 20, r.freedBytes() >> 20, r.collections(), r.millis());
                }));
    }

    private void cmdAdmin(Invocation inv, Output out) throws Exception {
        String sub = inv.args().isEmpty() ? "status" : inv.args().getFirst().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "unlock" -> {
                if (inv.args().size() < 2) {
                    throw new IllegalArgumentException("usage: admin unlock <secret>");
                }
                out.println(switch (unlock(inv.args().get(1).toCharArray())) {
                    case UnlockResult.Unlocked u -> "unlocked until " + u.until();
                    case UnlockResult.Denied d -> "denied (" + d.remainingAttempts() + " attempt(s) left)";
                    case UnlockResult.LockedOut l -> "too many failures; locked until " + l.until();
                    case UnlockResult.NotRequired n -> "no passphrase configured; admin commands are open";
                });
            }
            case "lock" -> {
                lock();
                out.println("admin session locked");
            }
            case "passwd" -> {
                if (inv.has("clear")) {
                    setPassphrase(null);
                    out.println("passphrase removed; admin commands are open");
                } else if (inv.args().size() >= 2) {
                    setPassphrase(inv.args().get(1).toCharArray());
                    lock();
                    out.println("passphrase set; session locked");
                } else {
                    throw new IllegalArgumentException("usage: admin passwd <new> | --clear");
                }
            }
            case "status" -> {
                out.printf("access control: %s%s, session: %s, audit: %s (%d entries this run)",
                        protectedMode() ? "on" : "off", policy.passphraseHash().isPresent() ? " (central passphrase)" : "",
                        unlocked() ? "unlocked" : "locked", audit.file(), audit.written());
                policy.describe().forEach(line -> out.println("  " + line));
                policy.problems().forEach(line -> out.println("  ! " + line));
            }
            case "hash" -> {
                if (inv.args().size() < 2) {
                    throw new IllegalArgumentException("usage: admin hash <passphrase>   (prints the value for"
                            + " admin.passphrase.hash in the central policy; nothing is stored)");
                }
                out.println("admin.passphrase.hash=" + hashPassphrase(inv.args().get(1).toCharArray()));
            }
            default -> throw new IllegalArgumentException("unknown admin subcommand '" + sub + "'");
        }
    }

    private void cmdAudit(Invocation inv, Output out, WorkbenchController controller) throws Exception {
        if (inv.flags().contains("export")) {
            requirePrivilege("audit --export");
            String since = inv.option("since", null);
            if (since == null) {
                throw new IllegalArgumentException("usage: audit --export --since yyyy-mm-dd [--out FILE]");
            }
            java.time.LocalDate day;
            try {
                day = java.time.LocalDate.parse(since.strip());
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("--since expects a date like 2025-06-01, got '" + since + "'");
            }
            Path target = inv.options().containsKey("out") ? Path.of(inv.option("out", ""))
                    : audit.file().resolveSibling("audit-export-" + day + "-to-"
                    + java.time.LocalDate.now(clock.withZone(java.time.ZoneId.systemDefault())) + ".log");
            int count = audit.export(day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(), target);
            audit.record(Level.SECURITY, Category.ADMIN, "op", "audit.export", "since", day.toString(),
                    "entries", String.valueOf(count), "file", target.toAbsolutePath().toString());
            out.println(count + " audit entries since " + day + " written to " + target.toAbsolutePath());
            return;
        }
        if (inv.options().containsKey("tail")) {
            requirePrivilege("audit --tail");
            for (String line : audit.tail(inv.intOption("tail", 20, 1, 500))) {
                out.println(line);
            }
            return;
        }
        HealthReport report = healthAudit(controller);
        out.println("health: " + report.worst());
        for (Finding f : report.findings()) {
            out.printf("  %-8s %-22s %s", f.severity(), f.code(), f.message());
        }
        StringBuilder sb = out.scratch().append("  metrics:");
        new java.util.TreeMap<>(report.metrics()).forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        out.println(sb);
    }

    private void cmdBlacklist(Invocation inv, Output out) throws Exception {
        if (inv.options().containsKey("add")) {
            String p = inv.option("add", "");
            out.println(addExclusion(p) ? "excluded: " + p : "already excluded: " + p);
        } else if (inv.options().containsKey("remove")) {
            String p = inv.option("remove", "");
            out.println(removeExclusion(p) ? "removed: " + p : "not in list: " + p);
        } else if (inv.options().containsKey("test")) {
            Path p = Path.of(inv.option("test", ""));
            out.println(p + (excluded(p) ? " is EXCLUDED" : " is allowed"));
        } else {
            for (String e : exclusions()) {
                out.println("  " + e);
            }
            out.println(exclusions().size() + " pattern(s)");
        }
    }

    private void cmdLimit(Invocation inv, Output out) throws Exception {
        if (inv.options().containsKey("memory")) {
            try {
                setMemoryCeiling(Double.parseDouble(inv.option("memory", "")));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--memory expects a fraction such as 0.80");
            }
        }
        if (inv.options().containsKey("workers")) {
            setWorkers(inv.intOption("workers", effective.workers(), Integer.MIN_VALUE, Integer.MAX_VALUE));
        }
        Settings s = effective;
        out.printf("memory ceiling %.0f%% of %d MB max heap (now %.0f%%), workers %d", s.memoryCeiling() * 100,
                Runtime.getRuntime().maxMemory() >> 20, HeapGuard.usageRatio() * 100, s.workers());
    }

    @Override
    public void close() {
        audit.close();
    }
}
