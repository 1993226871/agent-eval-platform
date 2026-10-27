package io.agenteval.experiments;
import io.agenteval.agents.AgentService;
import io.agenteval.catalog.*;
import io.agenteval.identity.WorkspaceActor;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.util.*;

public final class ExperimentService {
    private final ExperimentRepository repository;
    private final CatalogService catalog;
    private final AgentService agents;
    private final Clock clock;
    public ExperimentService(ExperimentRepository repository,CatalogService catalog,AgentService agents,Clock clock) { this.repository=repository; this.catalog=catalog; this.agents=agents; this.clock=clock; }
    public UUID start(WorkspaceActor actor,StartExperimentCommand command) {
        var request=JsonNodeFactory.instance.objectNode();
        request.put("agent_id",command.agentId().toString()).put("agent_version",command.agentVersion()).put("dataset_version_id",command.datasetVersionId().toString())
            .put("suite_version_id",command.suiteVersionId().toString()).put("repeat_count",command.repeatCount()).put("deadline",command.deadline().toString());
        if(command.sourceExperimentId()!=null) request.put("source_experiment_id",command.sourceExperimentId().toString());
        String hash=CanonicalJson.hash(request);
        var replay=repository.replay(actor,command.idempotencyKey(),hash); if(replay.isPresent()) return replay.get();
        if(!command.deadline().isAfter(clock.instant())) throw new IllegalArgumentException("Deadline has passed");
        agents.get(actor,command.agentId());
        var snapshot=prepare(actor,command.datasetVersionId(),command.suiteVersionId(),command.repeatCount());
        if(command.sourceExperimentId()!=null) { repository.get(actor,command.sourceExperimentId()); snapshot.put("source_experiment_id",command.sourceExperimentId().toString()); }
        return repository.start(actor,command,hash,snapshot,clock.instant());
    }
    public com.fasterxml.jackson.databind.JsonNode estimate(WorkspaceActor actor,UUID datasetId,UUID suiteId,int repeats) {
        return prepare(actor,datasetId,suiteId,repeats).path("cost_estimate");
    }
    private com.fasterxml.jackson.databind.node.ObjectNode prepare(WorkspaceActor actor,UUID datasetId,UUID suiteId,int repeats) {
        if(repeats<1 || repeats>20) throw new IllegalArgumentException("Invalid repeat count");
        var dataset=catalog.version(actor,datasetId); var suite=catalog.version(actor,suiteId);
        if(dataset.kind()!=ResourceKind.DATASET || suite.kind()!=ResourceKind.SUITE) throw new IllegalArgumentException("Dataset/suite published versions required");
        var datasetContent=dataset.content(); var suiteContent=suite.content();
        int trials=Math.multiplyExact(datasetContent.path("cases").size(),repeats);
        if(trials>1000) throw new IllegalArgumentException("Experiment exceeds 1000 trials");
        long bytes=datasetContent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+(long)suiteContent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+128;
        if(bytes>8*1024*1024) throw new IllegalArgumentException("Frozen experiment configuration exceeds limit");
        var snapshot=JsonNodeFactory.instance.objectNode(); snapshot.set("dataset",datasetContent); snapshot.set("suite",suiteContent);
        var estimate=snapshot.putObject("cost_estimate"); var assumptions=estimate.putArray("assumptions");
        var unestimated=estimate.putArray("unestimated_evaluator_versions");
        java.math.BigInteger total=java.math.BigInteger.ZERO; int calls=0,byokCalls=0;
        var evaluators=snapshot.putArray("evaluators");
        for(var entry:suite.content().path("entries")) {
            var version=catalog.version(actor,UUID.fromString(entry.path("evaluator_version").asText()));
            var content=version.content(); bytes+=content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+128L;
            if(bytes>8*1024*1024) throw new IllegalArgumentException("Frozen experiment configuration exceeds limit");
            var item=evaluators.addObject(); item.put("id",version.id().toString()); item.set("content",content);
            if(content.path("type").asText().equals("LLM")) {
                calls=Math.addExact(calls,trials);
                try {
                var config=content.path("config"); UUID model=UUID.fromString(config.path("model_profile_id").asText()),price=UUID.fromString(config.path("price_version_id").asText());
                var pricing=repository.pricing(model,price); int output=config.path("max_output_tokens").asInt();
                if(pricing.workspaceId()!=null && !pricing.workspaceId().equals(actor.workspaceId())) throw new io.agenteval.identity.ScopeViolationException();
                if(output<1 || output>pricing.maxOutputTokens()) throw new IllegalArgumentException("Invalid model output bound");
                long perCall=pricing.byok()?0:pricing.price().credits(pricing.inputTokenBound(),output);
                if(pricing.byok()) byokCalls=Math.addExact(byokCalls,trials);
                total=total.add(java.math.BigInteger.valueOf(perCall).multiply(java.math.BigInteger.valueOf(trials)));
                assumptions.addObject().put("evaluator_version_id",version.id().toString()).put("model_profile_id",model.toString()).put("price_version_id",price.toString())
                    .put("input_token_bound",pricing.inputTokenBound()).put("max_output_tokens",output).put("input_rate",Long.toString(pricing.price().inputRate()))
                    .put("output_rate",Long.toString(pricing.price().outputRate())).put("denominator",Long.toString(pricing.price().denominator())).put("per_call_credits",Long.toString(perCall));
                } catch(IllegalArgumentException | io.agenteval.identity.ScopeViolationException unavailable) { unestimated.add(version.id().toString()); }
            }
        }
        estimate.put("planned_credits",total.toString()).put("planned_calls",calls).put("trial_count",trials).put("basis","FIRST_ATTEMPT_RESERVATION_BOUND");
        estimate.put("byok_calls",byokCalls);
        if(!unestimated.isEmpty()) estimate.putNull("planned_credits");
        if(snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8*1024*1024) throw new IllegalArgumentException("Frozen experiment configuration exceeds limit");
        return snapshot;
    }
    public ExperimentRecord get(WorkspaceActor actor,UUID id) { return repository.get(actor,id); }
    public List<ExperimentRecord> list(WorkspaceActor actor,int offset,int limit) {
        if(offset<0 || limit<1 || limit>100) throw new IllegalArgumentException("Invalid page"); return repository.list(actor,offset,limit);
    }
    public List<TrialRecord> trials(WorkspaceActor actor,UUID id) { repository.get(actor,id); return repository.trials(actor,id); }
    public void cancel(WorkspaceActor actor,UUID id) { repository.cancel(actor,id); }
}
