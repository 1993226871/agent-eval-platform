package io.agenteval.catalog;
import io.agenteval.identity.WorkspaceActor;
import java.util.UUID;
public final class EvaluatorCatalogService {
    private final CatalogService catalog;
    public EvaluatorCatalogService(CatalogService catalog) { this.catalog=catalog; }
    public PublishedVersion publishEvaluator(WorkspaceActor actor,UUID id) {
        if(catalog.draft(actor,id).kind()!=ResourceKind.EVALUATOR) throw new IllegalArgumentException("Expected evaluator");
        return catalog.publish(actor,id);
    }
}
