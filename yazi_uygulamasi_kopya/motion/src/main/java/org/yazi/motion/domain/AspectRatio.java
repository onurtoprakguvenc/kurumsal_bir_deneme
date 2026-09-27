package org.yazi.motion.domain;

import java.util.Arrays;
import java.util.Optional;

public enum AspectRatio {
    RATIO_16_9("16:9"),
    RATIO_9_16("9:16"),
    RATIO_1_1("1:1"),
    RATIO_4_3("4:3"),
    RATIO_21_9("21:9");

    private final String label;

    AspectRatio(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Accepts either the label ("16:9") or the constant name ("RATIO_16_9"). */
    public static Optional<AspectRatio> parse(String value) {
        if (value == null) return Optional.empty();
        String v = value.trim();
        return Arrays.stream(values())
                .filter(r -> r.label.equals(v) || r.name().equalsIgnoreCase(v))
                .findFirst();
    }
}
