package io.agenteval.catalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
public record PublishedVersion(UUID id,UUID resourceId,ResourceKind kind,String name,JsonNode content,String contentHash,int number) {
    public PublishedVersion { content=content.deepCopy(); }
    @Override public JsonNode content() { return content.deepCopy(); }
}
