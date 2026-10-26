package io.agenteval.evaluation;
import io.agenteval.identity.WorkspaceActor;
import io.agenteval.telemetry.EvidenceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
public interface ReevaluationRepository {
    UUID create(WorkspaceActor actor,UUID trialId,UUID suiteVersionId,boolean includeLate,String key,JsonNode configuration,Supplier<EvidenceSnapshot> snapshot,Instant now);
}
