package io.agenteval.telemetry;
import java.net.URI;
public interface ArtifactStore {
    default void requireAvailable() {}
    URI uploadUrl(String stagingKey,long sizeBytes);
    void verifyAndSeal(String stagingKey,String objectKey,long sizeBytes,String sha256);
    URI downloadUrl(String objectKey);
    default void deleteObject(String objectKey) { throw new StorageUnavailableException(); }
}
