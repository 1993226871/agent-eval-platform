package io.agenteval.sdk;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
public record TaskContext(UUID trialId,String instruction,JsonNode input,Instant deadline,CancellationToken cancellation,ArtifactUploader uploader) {
    public TaskContext { input=input.deepCopy(); }
    public TaskContext(UUID trialId,String instruction,JsonNode input,Instant deadline,CancellationToken cancellation) { this(trialId,instruction,input,deadline,cancellation,null); }
    @Override public JsonNode input() { return input.deepCopy(); }
    public boolean cancelled() { return cancellation.isCancelled() || !Instant.now().isBefore(deadline); }
    public UUID artifact(java.nio.file.Path path,String name,String mediaType) { if(uploader==null) throw new IllegalStateException("Artifact upload unavailable"); return uploader.upload(path,name,mediaType); }
    @FunctionalInterface public interface ArtifactUploader { UUID upload(java.nio.file.Path path,String name,String mediaType); }
}
