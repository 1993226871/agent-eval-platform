package io.agenteval.telemetry;
import io.agenteval.identity.WorkspaceActor;
import java.time.Instant;
import java.util.UUID;
public interface EvidenceRepository {
    EvidenceSnapshot freeze(WorkspaceActor actor,UUID trialId,boolean includeLate,Instant now);
    EvidenceSnapshot load(WorkspaceActor actor,UUID id);
    int freezeReady(int limit,Instant now);
}
