package io.agenteval.catalog;
import io.agenteval.identity.WorkspaceActor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public final class CatalogService {
    public java.util.List<PublishedVersionSummary> versions(WorkspaceActor actor,UUID resourceId,int offset,int limit) {
        if(offset<0 || limit<1 || limit>100) throw new IllegalArgumentException("Invalid version page");
        repository.draft(actor,resourceId);
        return repository.versions(actor,resourceId,offset,limit);
    }
    private final CatalogRepository repository;
    public CatalogService(CatalogRepository repository) { this.repository=repository; }
    public CatalogResource create(WorkspaceActor actor,ResourceKind kind,String name,JsonNode content) {
        validateDraft(name,content); return repository.create(actor,Objects.requireNonNull(kind),name.strip(),content);
    }
    public CatalogResource draft(WorkspaceActor actor,UUID id) { return repository.draft(actor,id); }
    public List<CatalogResource> list(WorkspaceActor actor,ResourceKind kind,int offset,int limit) {
        if(offset<0 || limit<1 || limit>100) throw new IllegalArgumentException("Invalid page");
        return repository.list(actor,kind,offset,limit);
    }
    public CatalogResource update(WorkspaceActor actor,UUID id,long revision,String name,JsonNode content) {
        validateDraft(name,content); return repository.update(actor,id,revision,name.strip(),content);
    }
    public PublishedVersion version(WorkspaceActor actor,UUID id) { return repository.version(actor,id); }
    public void archive(WorkspaceActor actor,UUID id) { repository.archive(actor,id); }
    public PublishedVersion publish(WorkspaceActor actor,UUID id) {
        var resource=repository.draft(actor,id);
        if(resource.archived()) throw new CatalogConflictException();
        var content=resource.content();
        switch(resource.kind()) {
            case DATASET -> validateDataset(content);
            case EVALUATOR -> validateEvaluator(content);
            case SUITE -> validateSuite(actor,content);
        }
        return repository.publish(actor,resource,CanonicalJson.hash(content));
    }
    private void validateDraft(String name,JsonNode content) {
        if(name==null || name.isBlank() || name.length()>200 || content==null || !content.isObject()) throw new IllegalArgumentException("Name and object content required");
        if(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2*1024*1024) throw new IllegalArgumentException("Catalog content exceeds 2 MiB");
    }
    public static void validateDataset(JsonNode content) {
        var cases=content.path("cases"); if(!cases.isArray() || cases.isEmpty() || cases.size()>1000) throw new IllegalArgumentException("Dataset must have 1..1000 cases");
        for(var task:cases) {
            if(!task.isObject() || !task.path("instruction").isTextual() || task.path("instruction").asText().isBlank() || !task.has("input")) throw new IllegalArgumentException("Each case requires instruction and input");
            if(task.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>768*1024) throw new IllegalArgumentException("Task exceeds safe SDK response size");
            if(task.has("timeout_seconds") && (!task.get("timeout_seconds").isIntegralNumber() || task.get("timeout_seconds").asLong()<1 || task.get("timeout_seconds").asLong()>3600)) throw new IllegalArgumentException("Invalid task timeout");
        }
    }
    public static void validateEvaluator(JsonNode content) {
        if(!Set.of("RULE","LLM","PYTHON").contains(content.path("type").asText())) throw new IllegalArgumentException("Unknown evaluator type");
        if(!content.path("criterion_id").asText().matches("[a-zA-Z0-9_.-]{1,64}") || !content.path("config").isObject()) throw new IllegalArgumentException("Invalid criterion/config");
        if(!content.path("required_evidence").isArray()) throw new IllegalArgumentException("required_evidence must be explicit");
        if(!content.path("threshold").isNumber() || content.path("threshold").decimalValue().signum()<0 || content.path("threshold").decimalValue().compareTo(java.math.BigDecimal.ONE)>0) throw new IllegalArgumentException("Invalid threshold");
    }
    private void validateSuite(WorkspaceActor actor,JsonNode content) {
        var entries=content.path("entries"); if(!entries.isArray() || entries.isEmpty() || entries.size()>100) throw new IllegalArgumentException("Suite requires 1..100 entries");
        boolean required=false; var criteria=new HashSet<String>(); var ids=new HashSet<UUID>(); var weights=new HashMap<String,java.math.BigDecimal>();
        for(var item:entries) {
            JsonNode definition;
            if(item.has("definition")) {
                definition=item.get("definition");
                validateEvaluator(definition);
                var evidence=new ArrayList<String>(); definition.path("required_evidence").forEach(value -> evidence.add(value.asText()));
                var evaluator=new io.agenteval.evaluation.EvaluatorDefinition(UUID.randomUUID(),definition.path("criterion_id").asText(),definition.path("type").asText(),evidence,definition.path("threshold").decimalValue(),definition.path("config"));
                if(evaluator.type().equals("RULE")) new io.agenteval.evaluation.RuleEvaluator().validate(evaluator);
                if(evaluator.type().equals("LLM")) new io.agenteval.evaluation.JudgePromptBuilder().validate(evaluator);
                if(evaluator.type().equals("PYTHON") && definition.at("/config/code").asText().isBlank()) throw new IllegalArgumentException("Python code required");
            } else {
                UUID id=UUID.fromString(item.path("evaluator_version").asText());
                if(!ids.add(id)) throw new IllegalArgumentException("Duplicate evaluator version");
                var version=repository.version(actor,id);
                if(version.kind()!=ResourceKind.EVALUATOR) throw new IllegalArgumentException("Expected evaluator version");
                definition=version.content();
            }
            if(!criteria.add(definition.path("criterion_id").asText())) throw new IllegalArgumentException("Duplicate criterion identifier");
            String dimension=item.path("dimension").asText();
            if(!Set.of("outcome","process").contains(dimension) || !item.path("required_to_pass").isBoolean() || !item.path("weight").isNumber()) throw new IllegalArgumentException("Invalid suite item");
            var weight=item.get("weight").decimalValue(); if(weight.signum()<0) throw new IllegalArgumentException("Negative weight");
            weights.merge(dimension,weight,java.math.BigDecimal::add); required|=item.get("required_to_pass").asBoolean();
        }
        if(!required || weights.values().stream().anyMatch(weight -> weight.signum()==0)) throw new IllegalArgumentException("Suite requires a hard condition and positive dimension weights");
    }
}
