package org.example.admin;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.function.UnaryOperator;

/**
 * Machine-wide policy set by IT, read once at startup from a file normal users can read but not change
 * ({@code %ProgramData%\DocumentWorkbench\policy.properties} on Windows, {@code /etc/document-workbench/policy.properties}
 * elsewhere; the {@code dwb.policy} system property, set in the installed launcher, may point elsewhere).
 *
 * <p>Every key is optional. What the policy sets, users cannot change: the admin commands and the admin page show
 * those settings as locked. What it leaves out keeps working exactly as before (local {@code admin.properties},
 * environment variables, defaults), so an installation without a policy file behaves like one without this class.
 * See {@code deploy/policy.example.properties} for every key.</p>
 *
 * <p>The file is only as protected as its NTFS permissions: IT should grant Users read-only access (the deployment
 * script does). {@link #writableByUser()} lets the admin page warn when that was not done.</p>
 */
public final class CentralPolicy {

    public static final String LOCATION_PROPERTY = "dwb.policy";

    private final Path file;
    private final boolean present;
    private final boolean writableByUser;
    private final List<String> problems;
    private final String version;
    private final String passHash;
    private final List<String> exclusions;
    private final boolean replaceExclusions;
    private final Double memoryCeiling;
    private final Integer workers;
    private final Integer unlockMinutes;
    private final Boolean aiEnabled;
    private final Boolean webEnabled;
    private final Boolean lanEnabled;
    private final boolean lanRequireSecret;
    private final Path reportFolder;
    private final int reportMinutes;
    private final Path supportFolder;
    /** Environment-style overrides (DWB_AI_QUOTA, DWB_WEB_ENDPOINT, …) the policy imposes. */
    private final Map<String, String> environment;

    private CentralPolicy(Path file, boolean present, boolean writableByUser, List<String> problems, Properties p) {
        this.file = file;
        this.present = present;
        this.writableByUser = writableByUser;
        List<String> issues = new ArrayList<>(problems);
        this.version = text(p, "policy.version");
        this.passHash = text(p, "admin.passphrase.hash");
        if (passHash != null && !passHash.startsWith("pbkdf2$")) {
            issues.add("admin.passphrase.hash is not a pbkdf2 hash (create one with: dwb-cli --hash-passphrase)");
        }
        List<String> ex = new ArrayList<>();
        for (int i = 1; p.containsKey("exclude." + i); i++) {
            String v = p.getProperty("exclude." + i).strip();
            if (!v.isEmpty()) {
                ex.add(v);
            }
        }
        this.exclusions = List.copyOf(ex);
        this.replaceExclusions = "replace".equalsIgnoreCase(text(p, "exclude.mode"));
        this.memoryCeiling = number(p, "memory.ceiling", 0.50, 0.95, issues);
        Double w = number(p, "workers", 1, 8, issues);
        this.workers = w == null ? null : w.intValue();
        Double u = number(p, "unlock.minutes", 1, 240, issues);
        this.unlockMinutes = u == null ? null : u.intValue();
        this.aiEnabled = bool(p, "ai.enabled", issues);
        this.webEnabled = bool(p, "web.enabled", issues);
        this.lanEnabled = bool(p, "lan.enabled", issues);
        this.lanRequireSecret = Boolean.TRUE.equals(bool(p, "lan.requireSecret", issues));
        this.reportFolder = path(p, "report.folder", issues);
        Double minutes = number(p, "report.intervalMinutes", 5, 1_440, issues);
        this.reportMinutes = minutes == null ? 60 : minutes.intValue();
        this.supportFolder = path(p, "support.folder", issues);
        Map<String, String> env = new LinkedHashMap<>();
        map(p, env, "ai.daily", "DWB_AI_QUOTA");
        map(p, env, "ai.perMinute", "DWB_AI_RATE");
        map(p, env, "ai.hours", "DWB_AI_HOURS");
        map(p, env, "ai.days", "DWB_AI_DAYS");
        map(p, env, "web.provider", "DWB_WEB_PROVIDER");
        map(p, env, "web.endpoint", "DWB_WEB_ENDPOINT");
        map(p, env, "web.key", "DWB_WEB_KEY");
        map(p, env, "lan.trustMode", "DWB_TRUST");
        String trustMode = env.get("DWB_TRUST");
        if (trustMode != null && !trustMode.equalsIgnoreCase("zero") && !trustMode.equalsIgnoreCase("legacy")) {
            issues.add("lan.trustMode must be 'zero' or 'legacy', not '" + trustMode + "'");
            env.remove("DWB_TRUST");
        }
        this.environment = Map.copyOf(env);
        this.problems = List.copyOf(issues);
    }

    // ================================================================== loading

    /** No policy: every setting stays under local control. */
    public static CentralPolicy none() {
        return new CentralPolicy(null, false, false, List.of(), new Properties());
    }

    /** Where the policy is looked for on this machine. */
    public static Path defaultLocation() {
        String override = System.getProperty(LOCATION_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.strip());
        }
        String programData = System.getenv("ProgramData");
        if (programData != null && !programData.isBlank()) {
            return Path.of(programData, "DocumentWorkbench", "policy.properties");
        }
        return Path.of("/etc", "document-workbench", "policy.properties");
    }

    /** Loads the machine policy; never throws (an unreadable policy is reported through {@link #problems()}). */
    public static CentralPolicy loadDefault() {
        return load(defaultLocation());
    }

    public static CentralPolicy load(Path file) {
        Objects.requireNonNull(file, "file must not be null");
        Properties p = new Properties();
        List<String> problems = new ArrayList<>();
        boolean present = false;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                text = text.substring(1); // Notepad's UTF-8 byte-order mark would otherwise corrupt the first key
            }
            try (Reader r = new java.io.StringReader(text)) {
                p.load(r);
            }
            present = true;
        } catch (NoSuchFileException e) {
            // no central policy on this machine
        } catch (IOException | IllegalArgumentException e) {
            problems.add("policy file " + file + " unreadable: " + e.getMessage() + "; local settings apply");
        }
        boolean writable = present && Files.isWritable(file);
        return new CentralPolicy(file, present, writable, problems, p);
    }

    // ================================================================== accessors

    public boolean present() {
        return present;
    }

    public Path file() {
        return file;
    }

    /** The policy file exists but this user may change it: its settings are then advisory, not enforced. */
    public boolean writableByUser() {
        return writableByUser;
    }

    public List<String> problems() {
        return problems;
    }

    /** Free-text version label IT gives the policy ({@code policy.version}), shown in reports and the admin page. */
    public Optional<String> version() {
        return Optional.ofNullable(version);
    }

    public Optional<String> passphraseHash() {
        return Optional.ofNullable(passHash).filter(h -> h.startsWith("pbkdf2$"));
    }

    public List<String> exclusions() {
        return exclusions;
    }

    /** {@code exclude.mode=replace}: only the central list applies; otherwise it is added to the local list. */
    public boolean replacesExclusions() {
        return replaceExclusions;
    }

    public OptionalDouble memoryCeiling() {
        return memoryCeiling == null ? OptionalDouble.empty() : OptionalDouble.of(memoryCeiling);
    }

    public OptionalInt workers() {
        return workers == null ? OptionalInt.empty() : OptionalInt.of(workers);
    }

    public OptionalInt unlockMinutes() {
        return unlockMinutes == null ? OptionalInt.empty() : OptionalInt.of(unlockMinutes);
    }

    /** False only when the policy turns AI answers off. */
    public boolean aiAllowed() {
        return !Boolean.FALSE.equals(aiEnabled);
    }

    public boolean aiLocked() {
        return aiEnabled != null;
    }

    public boolean webAllowed() {
        return !Boolean.FALSE.equals(webEnabled);
    }

    public boolean webLocked() {
        return webEnabled != null || environment.keySet().stream().anyMatch(k -> k.startsWith("DWB_WEB_"));
    }

    public boolean lanAllowed() {
        return !Boolean.FALSE.equals(lanEnabled);
    }

    /**
     * LAN sharing only with authenticated peers: zero-trust mode, or {@code DWB_SECRET} in legacy mode; open mode is
     * refused.
     */
    public boolean lanRequiresSecret() {
        return lanRequireSecret;
    }

    public Optional<Path> reportFolder() {
        return Optional.ofNullable(reportFolder);
    }

    public int reportIntervalMinutes() {
        return reportMinutes;
    }

    public Optional<Path> supportFolder() {
        return Optional.ofNullable(supportFolder);
    }

    /** True when the policy fixes the named environment setting (e.g. {@code DWB_AI_QUOTA}). */
    public boolean overrides(String environmentKey) {
        return environment.containsKey(environmentKey);
    }

    /** {@code base} with the policy's values taking precedence, for the {@code fromEnvironment} factories. */
    public UnaryOperator<String> environment(UnaryOperator<String> base) {
        return key -> environment.containsKey(key) ? environment.get(key) : base.apply(key);
    }

    /** Human-readable summary of what the policy sets (never the passphrase hash or the web key). */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        if (!present) {
            out.add("no central policy (" + file + ")");
            return out;
        }
        out.add("policy " + file + version().map(v -> " · version " + v).orElse("")
                + (writableByUser ? " · WARNING: writable by this user, not enforced" : ""));
        if (passHash != null) {
            out.add("admin passphrase: set centrally");
        }
        if (!exclusions.isEmpty()) {
            out.add("exclusions (" + (replaceExclusions ? "replace" : "added to local") + "): "
                    + String.join(", ", exclusions));
        }
        memoryCeiling().ifPresent(v -> out.add(String.format(Locale.ROOT, "memory ceiling: %.2f", v)));
        workers().ifPresent(v -> out.add("workers: " + v));
        unlockMinutes().ifPresent(v -> out.add("admin session: " + v + " min"));
        if (aiEnabled != null) {
            out.add("AI answers: " + (aiEnabled ? "allowed" : "OFF"));
        }
        if (webEnabled != null) {
            out.add("web search: " + (webEnabled ? "allowed" : "OFF"));
        }
        if (lanEnabled != null || lanRequireSecret) {
            out.add("LAN sharing: " + (Boolean.FALSE.equals(lanEnabled) ? "OFF"
                    : lanRequireSecret ? "only authenticated (zero-trust, or DWB_SECRET in legacy mode)" : "allowed"));
        }
        environment.forEach((k, v) -> out.add(k + " = " + (k.equals("DWB_WEB_KEY") ? "(set)" : v)));
        reportFolder().ifPresent(f -> out.add("health report: " + f + " every " + reportMinutes + " min"));
        supportFolder().ifPresent(f -> out.add("support bundles: " + f));
        return out;
    }

    // ================================================================== parsing helpers

    private static String text(Properties p, String key) {
        String v = p.getProperty(key);
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static Double number(Properties p, String key, double min, double max, List<String> issues) {
        String v = text(p, key);
        if (v == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(v);
            if (d < min || d > max) {
                issues.add(key + " = " + v + " is outside " + min + "–" + max + "; ignored");
                return null;
            }
            return d;
        } catch (NumberFormatException e) {
            issues.add(key + " = " + v + " is not a number; ignored");
            return null;
        }
    }

    private static Boolean bool(Properties p, String key, List<String> issues) {
        String v = text(p, key);
        if (v == null) {
            return null;
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1", "evet", "açık" -> Boolean.TRUE;
            case "false", "no", "off", "0", "hayır", "kapalı" -> Boolean.FALSE;
            default -> {
                issues.add(key + " = " + v + " is not true/false; ignored");
                yield null;
            }
        };
    }

    private static Path path(Properties p, String key, List<String> issues) {
        String v = text(p, key);
        if (v == null) {
            return null;
        }
        try {
            return Path.of(v);
        } catch (RuntimeException e) {
            issues.add(key + " = " + v + " is not a valid path; ignored");
            return null;
        }
    }

    private static void map(Properties p, Map<String, String> env, String key, String environmentKey) {
        String v = text(p, key);
        if (v != null) {
            env.put(environmentKey, v);
        }
    }
}
