package io.agenteval.experiments;
import com.fasterxml.jackson.databind.JsonNode;
import io.agenteval.protocol.TrialExecutionStatus;
import java.util.UUID;
public record TrialRecord(UUID id,UUID experimentId,UUID workspaceId,int caseIndex,int attempt,String traceId,TrialExecutionStatus status,JsonNode task) {
    public TrialRecord { task=task.deepCopy(); }
    @Override public JsonNode task() { return task.deepCopy(); }
}
