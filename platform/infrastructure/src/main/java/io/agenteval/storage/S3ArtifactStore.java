package io.agenteval.storage;
import io.agenteval.telemetry.ArtifactStore;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.core.sync.RequestBody;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Staging is client-writable; only server-verified bytes reach the sealed namespace. */
public final class S3ArtifactStore implements ArtifactStore,AutoCloseable {
    private final S3Client client;
    private final S3Presigner signer;
    private final String bucket;
    public S3ArtifactStore(URI endpoint,String region,String accessKey,String secretKey,String bucket) {
        var credentials=StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey,secretKey));
        var config=S3Configuration.builder().pathStyleAccessEnabled(true).build();
        client=S3Client.builder().region(Region.of(region)).credentialsProvider(credentials).endpointOverride(endpoint).serviceConfiguration(config)
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30))).overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(90))).build();
        signer=S3Presigner.builder().region(Region.of(region)).credentialsProvider(credentials).endpointOverride(endpoint).serviceConfiguration(config).build(); this.bucket=bucket;
    }
    @Override public URI uploadUrl(String stagingKey,long sizeBytes) {
        return URI.create(signer.presignPutObject(b -> b.signatureDuration(Duration.ofMinutes(2)).putObjectRequest(r -> r.bucket(bucket).key(stagingKey).contentLength(sizeBytes).contentType("application/octet-stream"))).url().toString());
    }
    @Override public void verifyAndSeal(String stagingKey,String objectKey,long sizeBytes,String sha256) {
        if(sizeBytes<0 || sizeBytes>104857600) throw new IllegalArgumentException("Artifact size limit");
        Path temporary=null;
        try {
            temporary=Files.createTempFile("agent-eval-artifact-",".bin");
            var digest=MessageDigest.getInstance("SHA-256"); long count=0;
            try(var input=client.getObject(r -> r.bucket(bucket).key(stagingKey)); var output=Files.newOutputStream(temporary)) {
                if(input.response().contentLength()!=sizeBytes) throw new IllegalArgumentException("Artifact size mismatch");
                byte[] buffer=new byte[65536]; int read;
                while((read=input.read(buffer,0,(int)Math.min(buffer.length,sizeBytes-count+1)))!=-1) {
                    count+=read; if(count>sizeBytes) throw new IllegalArgumentException("Artifact size mismatch"); digest.update(buffer,0,read); output.write(buffer,0,read);
                }
            }
            if(count!=sizeBytes || !HexFormat.of().formatHex(digest.digest()).equals(sha256)) throw new IllegalArgumentException("Artifact checksum mismatch");
            // Upload the exact verified file, never a server-side copy of mutable staging.
            client.putObject(r -> r.bucket(bucket).key(objectKey).contentType("application/octet-stream").contentDisposition("attachment"),RequestBody.fromFile(temporary));
        } catch(java.io.IOException failure) { throw new IllegalStateException("Artifact storage failed",failure); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(java.io.IOException failure) { throw new IllegalStateException("Artifact temporary-file cleanup failed",failure); } }
    }
    @Override public URI downloadUrl(String objectKey) {
        return URI.create(signer.presignGetObject(b -> b.signatureDuration(Duration.ofMinutes(2)).getObjectRequest(r -> r.bucket(bucket).key(objectKey).responseContentDisposition("attachment").responseContentType("application/octet-stream"))).url().toString());
    }
    @Override public void close() { signer.close(); client.close(); }
}
