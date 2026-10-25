package io.agenteval.telemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.time.Instant;
public record SpanView(long id,String spanId,String parentSpanId,String name,String kind,String status,
    Instant startedAt,Instant endedAt,JsonNode input,JsonNode output,Long durationMs,String origin) {
    public SpanView { input=input==null?NullNode.instance:input.deepCopy(); output=output==null?NullNode.instance:output.deepCopy(); }
    @Override public JsonNode input() { return input.deepCopy(); }
    @Override public JsonNode output() { return output.deepCopy(); }
}
