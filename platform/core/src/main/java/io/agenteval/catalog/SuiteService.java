package io.agenteval.catalog;
import io.agenteval.identity.WorkspaceActor;
import java.util.UUID;
public final class SuiteService {
    private final CatalogService catalog;
    public SuiteService(CatalogService catalog) { this.catalog=catalog; }
    public PublishedVersion publishSuite(WorkspaceActor actor,UUID id) {
        if(catalog.draft(actor,id).kind()!=ResourceKind.SUITE) throw new IllegalArgumentException("Expected suite");
        return catalog.publish(actor,id);
    }
}
