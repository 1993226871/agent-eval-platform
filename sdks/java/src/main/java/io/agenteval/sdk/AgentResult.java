package io.agenteval.sdk;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
public record AgentResult(JsonNode output,List<UUID> artifactIds) {
    public AgentResult { Objects.requireNonNull(output); output=output.deepCopy(); artifactIds=List.copyOf(artifactIds); }
    public static AgentResult of(Object output) { return new AgentResult(JsonSupport.value(output),List.of()); }
    @Override public JsonNode output() { return output.deepCopy(); }
}
