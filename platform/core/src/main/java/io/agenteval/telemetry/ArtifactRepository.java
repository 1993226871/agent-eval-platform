package io.agenteval.telemetry;
import io.agenteval.identity.WorkspaceActor;
import io.agenteval.workers.WorkerPrincipal;
import io.agenteval.protocol.RegisterArtifactRequest;
import java.time.Instant;
import java.util.UUID;
public interface ArtifactRepository {
    ArtifactRecord reserve(WorkerPrincipal principal,UUID trial,String token,RegisterArtifactRequest request,Instant now);
    ArtifactRecord beginVerification(WorkerPrincipal principal,UUID trial,String token,UUID artifact,Instant now);
    void finishVerification(WorkerPrincipal principal,UUID trial,String token,ArtifactRecord artifact,Instant now);
    void abandonVerification(ArtifactRecord artifact);
    ArtifactRecord get(WorkspaceActor actor,UUID artifact);
    ArtifactRecord getEvidence(WorkspaceActor actor,UUID trialId,UUID snapshotId,UUID artifact);
}
