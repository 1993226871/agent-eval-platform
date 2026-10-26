package io.agenteval.experiments;
import com.fasterxml.jackson.databind.JsonNode;
import io.agenteval.identity.WorkspaceActor;
import java.util.*;
public interface ExperimentRepository {
    record ModelPricing(long inputTokenBound,int maxOutputTokens,io.agenteval.model.PriceVersion price) {}
    ModelPricing pricing(UUID modelId,UUID priceId);
    Optional<UUID> replay(WorkspaceActor actor,String key,String requestHash);
    UUID start(WorkspaceActor actor,StartExperimentCommand command,String requestHash,JsonNode snapshot,java.time.Instant now);
    ExperimentRecord get(WorkspaceActor actor,UUID id);
    List<ExperimentRecord> list(WorkspaceActor actor,int offset,int limit);
    List<TrialRecord> trials(WorkspaceActor actor,UUID experimentId);
    void cancel(WorkspaceActor actor,UUID experimentId);
}
