package org.example.util;

import java.util.Locale;

/** Version and environment facts for support bundles, health reports and the admin page. */
public final class AppInfo {

    private AppInfo() {
    }

    /**
     * The application version: the {@code dwb.version} system property (set by the installed launcher), else the
     * jar manifest's {@code Implementation-Version}, else {@code "dev"} (running from an IDE or the build folder).
     */
    public static String version() {
        String property = System.getProperty("dwb.version");
        if (property != null && !property.isBlank()) {
            return property.strip();
        }
        String manifest = AppInfo.class.getPackage() == null ? null : AppInfo.class.getPackage().getImplementationVersion();
        return manifest == null || manifest.isBlank() ? "dev" : manifest;
    }

    public static String javaVersion() {
        return System.getProperty("java.runtime.version", System.getProperty("java.version", "?")) + " ("
                + System.getProperty("java.vendor", "?") + ")";
    }

    public static String os() {
        return System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "") + " ("
                + System.getProperty("os.arch", "?") + ")";
    }

    /** Computer name, or "unknown"; used to name report files. */
    public static String host() {
        for (String env : new String[]{"COMPUTERNAME", "HOSTNAME"}) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException | RuntimeException e) {
            return "unknown";
        }
    }

    public static String user() {
        return System.getProperty("user.name", "unknown");
    }

    /** {@code host-user} reduced to characters that are safe in a file name on every system. */
    public static String machineId() {
        String raw = (host() + "-" + user()).toLowerCase(Locale.ROOT);
        String safe = raw.replaceAll("[^a-z0-9._-]+", "_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }
}
