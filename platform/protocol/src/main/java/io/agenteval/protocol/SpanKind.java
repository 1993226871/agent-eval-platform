package io.agenteval.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum SpanKind {
    AGENT, MODEL, TOOL, RETRIEVAL, CUSTOM;
    @JsonValue public String wire() { return name().toLowerCase(java.util.Locale.ROOT); }
    @JsonCreator public static SpanKind parse(String value) {
        return Arrays.stream(values()).filter(v -> v.wire().equals(value)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown span kind"));
    }
}
