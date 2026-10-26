package io.agenteval.telemetry;
import io.agenteval.identity.WorkspaceActor;
import io.agenteval.workers.WorkerPrincipal;
import io.agenteval.protocol.*;
import java.time.Clock;
import java.net.URI;
import java.util.UUID;
public final class ArtifactService {
    private final ArtifactRepository repository;
    private final ArtifactStore store;
    private final Clock clock;
    public ArtifactService(ArtifactRepository repository,ArtifactStore store,Clock clock) { this.repository=repository; this.store=store; this.clock=clock; }
    public ArtifactUploadGrant initialize(WorkerPrincipal principal,UUID trial,String token,RegisterArtifactRequest request) {
        store.requireAvailable();
        var artifact=repository.reserve(principal,trial,token,request,clock.instant());
        return new ArtifactUploadGrant(artifact.id(),store.uploadUrl(artifact.stagingKey(),artifact.sizeBytes()),clock.instant().plusSeconds(120),"application/octet-stream",artifact.sizeBytes());
    }
    public ArtifactRef complete(WorkerPrincipal principal,UUID trial,String token,UUID id) {
        store.requireAvailable();
        var artifact=repository.beginVerification(principal,trial,token,id,clock.instant());
        if(artifact.status().equals("AVAILABLE")) return artifact.reference();
        try {
            store.verifyAndSeal(artifact.stagingKey(),artifact.objectKey(),artifact.sizeBytes(),artifact.sha256());
            repository.finishVerification(principal,trial,token,artifact,clock.instant());
        } catch(RuntimeException failure) { repository.abandonVerification(artifact); throw failure; }
        return artifact.reference();
    }
    public URI download(WorkspaceActor actor,UUID id) {
        var artifact=repository.get(actor,id); if(!artifact.status().equals("AVAILABLE")) throw new EvidencePendingException(); return store.downloadUrl(artifact.objectKey());
    }
    public URI downloadEvidence(WorkspaceActor actor,UUID trialId,UUID snapshotId,ArtifactRef reference) {
        var artifact=repository.getEvidence(actor,trialId,snapshotId,reference.artifactId());
        if(!artifact.reference().equals(reference)) throw new io.agenteval.identity.ScopeViolationException();
        return store.downloadUrl(artifact.objectKey());
    }
}
