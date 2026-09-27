package org.yazi.motion.engines;

import java.util.regex.Pattern;

/**
 * Engine-agnosticism invariant: canonical text must not carry vendor CLI/API syntax
 * such as "--ar 16:9", "--v 6", "--seed 42" or "::2" weight suffixes.
 */
public final class VendorSyntax {
    private VendorSyntax() {}

    private static final Pattern FLAG_WITH_VALUE = Pattern.compile(
            "(?i)(?<!\\S)--[a-z][a-z0-9_-]*(?:\\s+(?!--)[^\\s,;.]+)?");
    private static final Pattern WEIGHT_SUFFIX = Pattern.compile("::\\s*-?\\d+(?:\\.\\d+)?");
    private static final Pattern DETECT = Pattern.compile("(?i)(?<!\\S)--[a-z]|::\\s*-?\\d");

    /** Removes vendor tokens and tidies the remaining whitespace. */
    public static String strip(String text) {
        if (text == null) return null;
        String out = FLAG_WITH_VALUE.matcher(text).replaceAll(" ");
        out = WEIGHT_SUFFIX.matcher(out).replaceAll(" ");
        return out.replaceAll("\\s+", " ").replaceAll("\\s+([,.;:!?])", "$1").trim();
    }

    public static boolean containsVendorSyntax(String text) {
        return text != null && DETECT.matcher(text).find();
    }
}
