package io.agenteval.evaluation;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import io.agenteval.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;

public final class JudgeTemplateService {
    public record Template(String id,String name,String criterionId,List<String> requiredEvidence,JsonNode rubric) {
        public Template { requiredEvidence=List.copyOf(requiredEvidence); rubric=rubric.deepCopy(); }
        @Override public JsonNode rubric() { return rubric.deepCopy(); }
    }
    private final CatalogService catalog;
    private final ModelProfileRepository models;
    private final List<Template> templates;
    public JudgeTemplateService(CatalogService catalog,ModelProfileRepository models,List<Template> templates) { this.catalog=catalog; this.models=models; this.templates=List.copyOf(templates); }
    public List<Template> list() { return templates; }
    public CatalogResource copy(WorkspaceActor actor,String templateId,UUID modelId,UUID priceId) {
        var template=templates.stream().filter(item -> item.id().equals(templateId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown Judge template"));
        var model=models.require(Objects.requireNonNull(modelId));
        model.requireWorkspace(actor.workspaceId());
        if(!model.enabled() || !model.price().id().equals(priceId) || model.maxRequestBytes()<4096) throw new IllegalArgumentException("Model is unavailable or request budget is too small");
        var content=JsonNodeFactory.instance.objectNode().put("type","LLM").put("criterion_id",template.criterionId()).put("threshold",new BigDecimal("0.75"));
        var required=content.putArray("required_evidence"); template.requiredEvidence().forEach(required::add);
        var config=content.putObject("config").put("model_profile_id",model.id().toString()).put("price_version_id",priceId.toString()).put("prompt_version","rubric-v1")
            .put("max_output_tokens",Math.min(500,model.maxOutputTokens())).put("max_input_bytes",Math.min(32768,(model.maxRequestBytes()-2048)/2));
        config.set("rubric",template.rubric());
        new JudgePromptBuilder().validate(new EvaluatorDefinition(UUID.randomUUID(),template.criterionId(),"LLM",template.requiredEvidence(),new BigDecimal("0.75"),config));
        return catalog.create(actor,ResourceKind.EVALUATOR,template.name(),content);
    }
}
