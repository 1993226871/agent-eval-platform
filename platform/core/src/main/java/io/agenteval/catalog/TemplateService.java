package io.agenteval.catalog;
import com.fasterxml.jackson.databind.JsonNode;
import io.agenteval.identity.WorkspaceActor;
import java.util.List;

public final class TemplateService {
    public record Template(String id,ResourceKind kind,String name,JsonNode content) {
        public Template { content=content.deepCopy(); }
        @Override public JsonNode content() { return content.deepCopy(); }
    }
    private final CatalogService catalog;
    private final List<Template> templates;
    public TemplateService(CatalogService catalog,List<Template> templates) { this.catalog=catalog; this.templates=List.copyOf(templates); }
    public List<Template> list() { return templates; }
    public record StarterBundle(PublishedVersion datasetVersion,PublishedVersion evaluatorVersion,PublishedVersion suiteVersion) {}
    public StarterBundle copyStarter(WorkspaceActor actor) {
        var dataset=copy(actor,"order-query");
        var evaluator=copy(actor,"amount-rule");
        var evaluatorVersion=catalog.publish(actor,evaluator.id());
        var item=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        item.put("evaluator_version",evaluatorVersion.id().toString()); item.put("dimension","outcome"); item.put("required_to_pass",true); item.put("weight",1);
        var content=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(); content.putArray("entries").add(item);
        var suite=catalog.create(actor,ResourceKind.SUITE,"基础订单评分体系",content);
        return new StarterBundle(catalog.publish(actor,dataset.id()),evaluatorVersion,catalog.publish(actor,suite.id()));
    }
    public CatalogResource copy(WorkspaceActor actor,String templateId) {
        var template=templates.stream().filter(item -> item.id().equals(templateId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown template"));
        return catalog.create(actor,template.kind(),template.name(),template.content());
    }
}
