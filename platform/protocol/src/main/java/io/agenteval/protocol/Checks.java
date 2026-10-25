package io.agenteval.protocol;

import java.util.Objects;

final class Checks {
    private Checks() {}
    static <T> T required(T value, String name) { return Objects.requireNonNull(value, name); }
    static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
    static String hexId(String value, int length, String name) {
        if (value == null || !value.matches("[0-9a-f]{" + length + "}") || value.chars().allMatch(c -> c == '0'))
            throw new IllegalArgumentException(name + " must be a nonzero lowercase hex identifier");
        return value;
    }
}
