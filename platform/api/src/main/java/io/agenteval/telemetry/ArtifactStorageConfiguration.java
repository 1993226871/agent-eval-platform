package io.agenteval.telemetry;
import io.agenteval.storage.S3ArtifactStore;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
@Configuration
public class ArtifactStorageConfiguration {
    @Bean(destroyMethod="close") @ConditionalOnProperty(name="app.storage.enabled",havingValue="true")
    S3ArtifactStore s3ArtifactStore(@Value("${app.storage.endpoint}") URI endpoint,@Value("${app.storage.region:us-east-1}") String region,
        @Value("${app.storage.access-key}") String accessKey,@Value("${app.storage.secret-key}") String secretKey,@Value("${app.storage.bucket}") String bucket) {
        return new S3ArtifactStore(endpoint,region,accessKey,secretKey,bucket);
    }
    @Bean @ConditionalOnProperty(name="app.storage.enabled",havingValue="false",matchIfMissing=true)
    ArtifactStore disabledArtifactStore() { return new ArtifactStore() {
        public void requireAvailable() { throw new StorageUnavailableException(); }
        public URI uploadUrl(String key,long size) { throw new StorageUnavailableException(); }
        public void verifyAndSeal(String stage,String sealed,long size,String hash) { throw new StorageUnavailableException(); }
        public URI downloadUrl(String key) { throw new StorageUnavailableException(); }
    }; }
}
