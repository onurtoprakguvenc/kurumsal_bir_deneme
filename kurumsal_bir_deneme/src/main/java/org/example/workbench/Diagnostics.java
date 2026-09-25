package org.example.workbench;

import org.example.admin.AdminControlEngine;
import org.example.admin.CentralPolicy;
import org.example.core.QuotaGate;
import org.example.core.SearchIndex;
import org.example.project.ProjectProfile;
import org.example.project.ProjectWorkspaceManager.ActiveProject;
import org.example.storage.IndexStorageEngine;
import org.example.util.AppInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What IT needs to see about one installation — version, machine, memory, policy, index health — collected without
 * any document content. Used by the support bundle ({@code support-bundle}) and the shared-folder health report
 * ({@link HealthReporter}).
 *
 * <p>Privacy: no document text, no search queries and no file contents are ever included. Audit entries keep file
 * paths (IT needs them to diagnose access problems) but their {@code query=} values are redacted and failed
 * commands are reduced to the command name.</p>
 */
public final class Diagnostics {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern QUERY = Pattern.compile("(query=)(\"(?:[^\"\\\\]|\\\\.)*\"|\\S+)");
    private static final Pattern COMMAND = Pattern.compile("(command=)(\"(?:[^\"\\\\]|\\\\.)*\"|\\S+)");
    private static final int AUDIT_LINES = 500;

    private Diagnostics() {
    }

    /** Key facts in a stable order; values are single-line and contain no document content. */
    public static Map<String, String> facts(ExtendedWorkbenchController ext) {
        Map<String, String> f = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        f.put("time", LocalDateTime.now().toString());
        f.put("version", AppInfo.version());
        f.put("host", AppInfo.host());
        f.put("user", AppInfo.user());
        f.put("os", AppInfo.os());
        f.put("java", AppInfo.javaVersion());
        f.put("cpus", String.valueOf(rt.availableProcessors()));
        f.put("heapMaxMB", String.valueOf(rt.maxMemory() >> 20));
        f.put("heapUsedMB", String.valueOf(used >> 20));
        f.put("heapUsedPercent", String.valueOf(Math.round(100.0 * used / Math.max(1, rt.maxMemory()))));
        f.put("locale", Locale.getDefault().toLanguageTag());
        f.put("timeZone", ZoneId.systemDefault().getId());
        f.put("workspace", ext.projects().home().toString());
        CentralPolicy policy = ext.admin().policy();
        f.put("policy", policy.present() ? policy.file() + policy.version().map(v -> " (" + v + ")").orElse("")
                + (policy.writableByUser() ? " WRITABLE-BY-USER" : "") : "none");
        f.put("policyProblems", String.valueOf(policy.problems().size()));
        AdminControlEngine.Settings s = ext.admin().settings();
        f.put("memoryCeiling", String.format(Locale.ROOT, "%.2f", s.memoryCeiling()));
        f.put("workers", String.valueOf(s.workers()));
        f.put("exclusions", String.valueOf(s.exclusions().size()));
        f.put("adminProtected", String.valueOf(ext.admin().protectedMode()));
        f.put("aiConfigured", String.valueOf(ext.aiConfigured()));
        f.put("aiAllowedByPolicy", String.valueOf(policy.aiAllowed()));
        f.put("webConfigured", String.valueOf(ext.web().config().configured()));
        Optional<ActiveProject> active = ext.projects().active();
        f.put("projects", String.valueOf(ext.projects().list().size()));
        if (active.isPresent()) {
            WorkbenchController c = active.get().controller();
            SearchIndex.Stats st = c.workbench().stats();
            f.put("activeProject", active.get().profile().name());
            f.put("documents", String.valueOf(st.documents()));
            f.put("chunks", String.valueOf(st.chunks()));
            f.put("terms", String.valueOf(st.terms()));
            f.put("snapshotKB", String.valueOf(size(c.storage().file()) >> 10));
            f.put("unsavedChanges", String.valueOf(c.dirty()));
            f.put("watchedFiles", String.valueOf(ext.projects().watchedFiles()));
            QuotaGate.Snapshot q = c.workbench().quota().snapshot();
            f.put("aiQuota", q.limit() == Integer.MAX_VALUE ? "unlimited" : q.used() + "/" + q.limit());
            AdminControlEngine.HealthReport health = ext.admin().healthAudit(c);
            f.put("health", health.worst().name());
            f.put("findings", String.join(",", health.findings().stream().map(AdminControlEngine.Finding::code).toList()));
        } else {
            f.put("activeProject", "none");
        }
        f.put("auditWritable", String.valueOf(ext.admin().audit().lastError() == null));
        return f;
    }

    /** Where a support bundle goes by default: the policy's support folder, else the Desktop, else the workspace. */
    public static Path defaultBundleFolder(ExtendedWorkbenchController ext) {
        Optional<Path> central = ext.admin().policy().supportFolder().filter(Files::isDirectory);
        if (central.isPresent()) {
            return central.get();
        }
        Path desktop = Path.of(System.getProperty("user.home"), "Desktop");
        return Files.isDirectory(desktop) ? desktop : ext.projects().home();
    }

    /**
     * Writes {@code dwb-support-<host>-<user>-<time>.zip} into {@code folder}: a summary, the health audit, the
     * snapshot check, the policy and the last audit entries (redacted). Returns the zip's path.
     */
    public static Path writeSupportBundle(ExtendedWorkbenchController ext, Path folder) throws IOException {
        Files.createDirectories(folder);
        Path zip = folder.resolve("dwb-support-" + AppInfo.machineId() + "-" + LocalDateTime.now().format(STAMP) + ".zip");
        try (OutputStream raw = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(raw)) {
            StringBuilder summary = new StringBuilder("Document Workbench support bundle\n");
            summary.append("(no document contents, search queries or file contents are included)\n\n");
            facts(ext).forEach((k, v) -> summary.append(String.format(Locale.ROOT, "%-20s %s%n", k, v)));
            put(out, "summary.txt", summary.toString());

            StringBuilder policy = new StringBuilder();
            ext.admin().policy().describe().forEach(l -> policy.append(l).append('\n'));
            ext.admin().policy().problems().forEach(l -> policy.append("! ").append(l).append('\n'));
            policy.append("\neffective exclusions:\n");
            ext.admin().exclusions().forEach(e -> policy.append("  ").append(e)
                    .append(ext.admin().centralExclusion(e) ? "   (central)" : "").append('\n'));
            put(out, "policy.txt", policy.toString());

            Optional<ActiveProject> active = ext.projects().active();
            if (active.isPresent()) {
                WorkbenchController c = active.get().controller();
                AdminControlEngine.HealthReport health = ext.admin().healthAudit(c);
                StringBuilder h = new StringBuilder("health: " + health.worst() + "\n");
                health.findings().forEach(fd -> h.append(String.format(Locale.ROOT, "  %-8s %-22s %s%n",
                        fd.severity(), fd.code(), fd.message())));
                h.append("\nmetrics:\n");
                new java.util.TreeMap<>(health.metrics()).forEach((k, v) -> h.append("  ").append(k).append(" = ")
                        .append(v).append('\n'));
                put(out, "health.txt", h.toString());

                StringBuilder snap = new StringBuilder();
                for (IndexStorageEngine.FileCheck check : c.storage().verify()) {
                    snap.append(String.format(Locale.ROOT, "%-15s %-8s %d docs %d KB %s%n", check.origin(),
                            !check.present() ? "absent" : check.valid() ? "OK" : "DAMAGED", check.documents(),
                            check.bytes() >> 10, check.problem() == null ? "" : check.problem()));
                }
                put(out, "snapshot.txt", snap.toString());
            }

            StringBuilder projects = new StringBuilder();
            for (ProjectProfile p : ext.projects().list()) {
                projects.append(p.name()).append("  ").append(p.root()).append('\n');
            }
            put(out, "projects.txt", projects.toString());

            List<String> lines = new ArrayList<>();
            for (String line : ext.admin().audit().tail(AUDIT_LINES)) {
                lines.add(redact(line));
            }
            put(out, "audit-tail.log", String.join("\n", lines) + "\n");
        }
        ext.admin().audit().record(org.example.admin.AuditLog.Level.INFO, org.example.admin.AuditLog.Category.ADMIN,
                "op", "support-bundle", "file", zip.toString());
        return zip;
    }

    /** Removes search text from an audit line: {@code query=} values entirely, commands down to their name. */
    static String redact(String line) {
        Matcher q = QUERY.matcher(line);
        String out = q.replaceAll("$1<redacted>");
        Matcher c = COMMAND.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (c.find()) {
            String value = c.group(2).replace("\"", "").strip();
            int space = value.indexOf(' ');
            String verb = space < 0 ? value : value.substring(0, space);
            c.appendReplacement(sb, Matcher.quoteReplacement(c.group(1) + verb + (space < 0 ? "" : " <redacted>")));
        }
        c.appendTail(sb);
        return sb.toString();
    }

    private static void put(ZipOutputStream out, String name, String text) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }
}
