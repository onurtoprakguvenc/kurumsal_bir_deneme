package org.example.util;

import java.util.Locale;

/** Human-readable transfer speeds and remaining times for the (Turkish) status bar. Pure functions, no toolkit. */
public final class Units {

    private Units() {
    }

    /** {@code 512 KB/s}, {@code 84.1 MB/s}, {@code 1.12 GB/s}; {@code 0 B/s} for anything not positive. */
    public static String rate(double bytesPerSecond) {
        if (!(bytesPerSecond > 0)) {
            return "0 B/s";
        }
        if (bytesPerSecond < 1024) {
            return String.format(Locale.ROOT, "%.0f B/s", bytesPerSecond);
        }
        if (bytesPerSecond < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024);
        }
        if (bytesPerSecond < 1024.0 * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB/s", bytesPerSecond / (1024.0 * 1024 * 1024));
    }

    /**
     * Remaining time in Turkish: {@code 12 sn}, {@code 3 dk 04 sn}, {@code 1 sa 02 dk}, {@code 2 gün 3 sa};
     * {@code null} when unknown (negative).
     */
    public static String remainingTr(long seconds) {
        if (seconds < 0) {
            return null;
        }
        if (seconds < 60) {
            return seconds + " sn";
        }
        if (seconds < 3_600) {
            return String.format(Locale.ROOT, "%d dk %02d sn", seconds / 60, seconds % 60);
        }
        if (seconds < 86_400) {
            return String.format(Locale.ROOT, "%d sa %02d dk", seconds / 3_600, seconds / 60 % 60);
        }
        return String.format(Locale.ROOT, "%d gün %d sa", seconds / 86_400, seconds / 3_600 % 24);
    }
}
