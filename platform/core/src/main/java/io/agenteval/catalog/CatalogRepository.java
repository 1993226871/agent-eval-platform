package io.agenteval.catalog;
import io.agenteval.identity.WorkspaceActor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
public interface CatalogRepository {
    CatalogResource create(WorkspaceActor actor,ResourceKind kind,String name,JsonNode content);
    CatalogResource draft(WorkspaceActor actor,UUID id);
    List<CatalogResource> list(WorkspaceActor actor,ResourceKind kind,int offset,int limit);
    CatalogResource update(WorkspaceActor actor,UUID id,long revision,String name,JsonNode content);
    PublishedVersion publish(WorkspaceActor actor,CatalogResource expected,String hash);
    PublishedVersion version(WorkspaceActor actor,UUID versionId);
    List<PublishedVersionSummary> versions(WorkspaceActor actor,UUID resourceId,int offset,int limit);
    void archive(WorkspaceActor actor,UUID id);
}
