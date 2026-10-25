package io.agenteval.catalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
public record CatalogResource(UUID id,UUID workspaceId,ResourceKind kind,String name,JsonNode content,boolean archived,long revision) {
    public CatalogResource { content=content.deepCopy(); }
    @Override public JsonNode content() { return content.deepCopy(); }
}
