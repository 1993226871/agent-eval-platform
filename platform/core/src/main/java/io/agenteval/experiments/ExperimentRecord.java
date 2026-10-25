package io.agenteval.experiments;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
public record ExperimentRecord(UUID id,UUID workspaceId,UUID agentId,String agentVersion,UUID datasetVersionId,UUID suiteVersionId,
    int repeatCount,Instant deadline,String status,JsonNode snapshot) {
    public ExperimentRecord { snapshot=snapshot.deepCopy(); }
    @Override public JsonNode snapshot() { return snapshot.deepCopy(); }
}
