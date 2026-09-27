package org.yazi.motion.domain;

import java.util.List;
import java.util.Objects;

final class DomainValidation {
    private DomainValidation() {}

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must be non-empty");
        return value;
    }

    static double requireRange(double value, double min, double max, String field) {
        if (Double.isNaN(value) || value < min || value > max) {
            throw new IllegalArgumentException(field + " must be in [" + min + ", " + max + "], got " + value);
        }
        return value;
    }

    static double requirePositive(double value, String field) {
        if (!(value > 0) || Double.isInfinite(value)) throw new IllegalArgumentException(field + " must be positive, got " + value);
        return value;
    }

    static <T> List<T> immutable(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    static <T> T require(T value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }
}
