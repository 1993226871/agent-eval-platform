package io.agenteval.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record TraceEvent(String schemaVersion, UUID eventId, UUID trialId, String traceId,
    String spanId, String parentSpanId, EventType eventType, SpanKind spanKind, String name,
    long sequence, Instant occurredAt, JsonNode payload, CaptureMetadata capture) {
    public TraceEvent {
        if (!"1.0".equals(schemaVersion)) throw new IllegalArgumentException("Unsupported schema_version");
        Checks.required(eventId,"event_id"); Checks.required(trialId,"trial_id");
        Checks.hexId(traceId,32,"trace_id"); Checks.hexId(spanId,16,"span_id");
        if (parentSpanId != null) {
            Checks.hexId(parentSpanId,16,"parent_span_id");
            if (spanId.equals(parentSpanId)) throw new IllegalArgumentException("Span cannot parent itself");
        }
        Checks.required(eventType,"event_type"); Checks.required(spanKind,"span_kind");
        Checks.text(name,"name"); if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        Checks.required(occurredAt,"occurred_at"); Checks.required(payload,"payload"); Checks.required(capture,"capture");
        payload = payload.deepCopy();
    }
    @Override public JsonNode payload() { return payload.deepCopy(); }
}
