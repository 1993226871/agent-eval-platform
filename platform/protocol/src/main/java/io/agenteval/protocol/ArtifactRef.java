package io.agenteval.protocol;
import java.util.UUID;

/** A stored object reference, never a client-selected server file path. */
public record ArtifactRef(UUID artifactId,String name,String mediaType,long sizeBytes,String sha256) {
    public ArtifactRef {
        Checks.required(artifactId,"artifact_id"); Checks.text(name,"name"); Checks.text(mediaType,"media_type");
        if(sizeBytes<0) throw new IllegalArgumentException("size_bytes must be nonnegative");
        if(sha256==null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 must be lowercase hex");
    }
}
