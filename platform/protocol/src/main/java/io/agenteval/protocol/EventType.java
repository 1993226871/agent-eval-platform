package io.agenteval.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum EventType {
    SPAN_STARTED("span.started"), SPAN_COMPLETED("span.completed"), SPAN_FAILED("span.failed"),
    LOG("log"), ARTIFACT_CREATED("artifact.created");
    private final String wire;
    EventType(String wire) { this.wire=wire; }
    @JsonValue public String wire() { return wire; }
    @JsonCreator public static EventType parse(String value) {
        return Arrays.stream(values()).filter(v -> v.wire.equals(value)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown event type"));
    }
}
