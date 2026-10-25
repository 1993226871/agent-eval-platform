package io.agenteval.protocol;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
public record ArtifactUploadGrant(UUID artifactId,URI uploadUrl,Instant expiresAt,String contentType,long contentLength) {}
