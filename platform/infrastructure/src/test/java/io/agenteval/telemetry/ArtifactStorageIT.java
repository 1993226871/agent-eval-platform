package io.agenteval.telemetry;
import io.agenteval.storage.S3ArtifactStore;
import io.agenteval.protocol.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ArtifactStorageIT {
    static GenericContainer<?> s3;
    static S3ArtifactStore store;
    @BeforeAll static void start() {
        s3=new GenericContainer<>("adobe/s3mock:4.11.0").withExposedPorts(9090).withEnv("initialBuckets","evidence"); s3.start();
        store=new S3ArtifactStore(URI.create("http://"+s3.getHost()+":"+s3.getMappedPort(9090)),"us-east-1","test-key","test-secret","evidence");
    }
    @AfterAll static void stop() { if(store!=null) store.close(); if(s3!=null) s3.stop(); }
    @Test void presignsPublicEndpointWhileStorageOperationsUsePrivateEndpoint() {
        var internal=URI.create("http://"+s3.getHost()+":"+s3.getMappedPort(9090));
        try(var configured=new S3ArtifactStore(internal,URI.create("https://evidence.example.test:9443"),"us-east-1","test-key","test-secret","evidence")) {
            assertThat(configured.uploadUrl("staging/test",3).getHost()).isEqualTo("evidence.example.test");
            assertThat(configured.downloadUrl("sealed/test").getPort()).isEqualTo(9443);
            configured.deleteObject("sealed/nonexistent");
        }
    }
    @Test void sealsVerifiedBytesSeparatelyFromReusableUploadUrl() throws Exception {
        var content="verified report".getBytes(StandardCharsets.UTF_8); String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String stage="staging/"+UUID.randomUUID(),sealed="sealed/"+UUID.randomUUID();
        var upload=store.uploadUrl(stage,content.length);
        try(var client=HttpClient.newHttpClient()) {
            assertThat(client.send(HttpRequest.newBuilder(upload).PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            store.verifyAndSeal(stage,sealed,content.length,hash);
            client.send(HttpRequest.newBuilder(upload).PUT(HttpRequest.BodyPublishers.ofByteArray("malicious later overwrite".getBytes(StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.discarding());
            var response=client.send(HttpRequest.newBuilder(store.downloadUrl(sealed)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.body()).isEqualTo("verified report");
            assertThat(store.downloadUrl(sealed).getQuery()).contains("response-content-disposition=attachment","response-content-type=application/octet-stream");
        }
    }
    @Test void rejectsWrongChecksumAndSizeWithoutPublishingObject() throws Exception {
        String stage="staging/"+UUID.randomUUID(),sealed="sealed/"+UUID.randomUUID();
        try(var client=HttpClient.newHttpClient()) {
            client.send(HttpRequest.newBuilder(store.uploadUrl(stage,3)).PUT(HttpRequest.BodyPublishers.ofString("abc")).build(),HttpResponse.BodyHandlers.discarding());
            assertThatThrownBy(() -> store.verifyAndSeal(stage,sealed,3,"0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.verifyAndSeal(stage,sealed,2,"0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
            assertThat(client.send(HttpRequest.newBuilder(store.downloadUrl(sealed)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
        }
    }
    @Test void deletesStagingAndSealedObjectsIdempotently() throws Exception {
        String stage="staging/"+UUID.randomUUID(),sealed="sealed/"+UUID.randomUUID();
        try(var client=HttpClient.newHttpClient()) {
            var content="abc".getBytes(StandardCharsets.UTF_8);
            assertThat(client.send(HttpRequest.newBuilder(store.uploadUrl(stage,3)).PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            store.verifyAndSeal(stage,sealed,3,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
            store.deleteObject(stage); store.deleteObject(sealed); store.deleteObject(stage); store.deleteObject(sealed);
            assertThat(client.send(HttpRequest.newBuilder(store.downloadUrl(stage)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
            assertThat(client.send(HttpRequest.newBuilder(store.downloadUrl(sealed)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
        }
    }
}
