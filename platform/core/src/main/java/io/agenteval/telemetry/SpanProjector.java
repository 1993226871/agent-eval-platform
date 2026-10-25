package io.agenteval.telemetry;
import io.agenteval.protocol.*;
import java.util.Objects;

/** Pure projection of immutable events; delivery order never determines lifecycle order. */
public final class SpanProjector {
    public SpanView project(SpanView current,TraceEvent event) {
        if(!current.spanId().equals(event.spanId()) || !Objects.equals(current.parentSpanId(),event.parentSpanId()) || !current.name().equals(event.name()) || !current.kind().equals(event.spanKind().wire())) throw new TelemetryConflictException();
        var input=current.input(); var output=current.output(); var started=current.startedAt(); var ended=current.endedAt(); var duration=current.durationMs(); String status=current.status();
        if(event.eventType()==EventType.SPAN_STARTED) {
            var next=event.payload().get("input");
            if(started!=null && (!started.equals(event.occurredAt()) || !Objects.equals(input,next==null?com.fasterxml.jackson.databind.node.NullNode.instance:next))) throw new TelemetryConflictException();
            started=event.occurredAt(); input=next; if(ended==null) status="RUNNING";
        } else if(event.eventType()==EventType.SPAN_COMPLETED || event.eventType()==EventType.SPAN_FAILED) {
            if(ended!=null) throw new TelemetryConflictException();
            ended=event.occurredAt(); output=event.payload().get("output"); status=event.eventType()==EventType.SPAN_FAILED?"FAILED":"COMPLETED";
            var raw=event.payload().get("duration_ms");
            if(raw!=null) { if(!raw.isIntegralNumber() || !raw.canConvertToLong() || raw.asLong()<0) throw new IllegalArgumentException("Invalid duration_ms"); duration=raw.asLong(); }
        }
        return new SpanView(current.id(),current.spanId(),current.parentSpanId(),current.name(),current.kind(),status,started,ended,input,output,duration,"worker_reported");
    }
}
