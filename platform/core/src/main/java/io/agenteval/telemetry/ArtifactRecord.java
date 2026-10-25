package io.agenteval.telemetry;
import io.agenteval.protocol.ArtifactRef;
import java.util.UUID;
public record ArtifactRecord(UUID id,UUID trialId,UUID workspaceId,String name,String mediaType,long sizeBytes,String sha256,String stagingKey,String objectKey,String status,UUID verificationToken) {
    public ArtifactRef reference() { return new ArtifactRef(id,name,mediaType,sizeBytes,sha256); }
}
