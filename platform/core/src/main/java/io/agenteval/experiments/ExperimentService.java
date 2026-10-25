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
        String hash=CanonicalJson.hash(request);
        var replay=repository.replay(actor,command.idempotencyKey(),hash); if(replay.isPresent()) return replay.get();
        if(!command.deadline().isAfter(clock.instant())) throw new IllegalArgumentException("Deadline has passed");
        agents.get(actor,command.agentId());
        var dataset=catalog.version(actor,command.datasetVersionId()); var suite=catalog.version(actor,command.suiteVersionId());
        if(dataset.kind()!=ResourceKind.DATASET || suite.kind()!=ResourceKind.SUITE) throw new IllegalArgumentException("Dataset/suite published versions required");
        if((long)dataset.content().path("cases").size()*command.repeatCount()>1000) throw new IllegalArgumentException("Experiment exceeds 1000 trials");
        var snapshot=JsonNodeFactory.instance.objectNode(); snapshot.set("dataset",dataset.content()); snapshot.set("suite",suite.content());
        var evaluators=snapshot.putArray("evaluators");
        for(var entry:suite.content().path("entries")) {
            var version=catalog.version(actor,UUID.fromString(entry.path("evaluator_version").asText()));
            var item=evaluators.addObject(); item.put("id",version.id().toString()); item.set("content",version.content());
        }
        return repository.start(actor,command,hash,snapshot,clock.instant());
    }
    public ExperimentRecord get(WorkspaceActor actor,UUID id) { return repository.get(actor,id); }
    public List<ExperimentRecord> list(WorkspaceActor actor,int offset,int limit) {
        if(offset<0 || limit<1 || limit>100) throw new IllegalArgumentException("Invalid page"); return repository.list(actor,offset,limit);
    }
    public List<TrialRecord> trials(WorkspaceActor actor,UUID id) { repository.get(actor,id); return repository.trials(actor,id); }
    public void cancel(WorkspaceActor actor,UUID id) { repository.cancel(actor,id); }
}
