package io.agenteval.sdk;
import io.agenteval.protocol.*;
import java.time.*;
import java.util.*;
public interface WorkerTransport extends AutoCloseable {
    UUID register(UUID agentId);
    Optional<LeaseGrant> claim(UUID workerId,UUID agentId);
    void started(LeaseGrant grant);
    Heartbeat heartbeat(LeaseGrant grant);
    List<UUID> uploadEvents(LeaseGrant grant,List<TraceEvent> events);
    void complete(LeaseGrant grant,CompletionRequest completion);
    default UUID uploadBytes(LeaseGrant grant,byte[] content,String name,String mediaType,String idempotencyKey) { throw new UnsupportedOperationException("Artifact transport unavailable"); }
    default UUID uploadArtifact(LeaseGrant grant,java.nio.file.Path path,String name,String mediaType) {
        try(var input=java.nio.file.Files.newInputStream(path)) {
            byte[] content=input.readNBytes(100*1024*1024+1); if(content.length>100*1024*1024) throw new IllegalArgumentException("Artifact size limit");
            return uploadBytes(grant,content,name==null?path.getFileName().toString():name,mediaType,UUID.randomUUID().toString());
        } catch(java.io.IOException unavailable) { throw new IllegalArgumentException("Cannot read selected artifact"); }
    }
    default Duration heartbeatInterval() { return Duration.ofSeconds(15); }
    default List<String> secrets() { return List.of(); }
    @Override default void close() {}
    record Heartbeat(boolean cancelRequested,Instant leaseExpiresAt) {}
}
