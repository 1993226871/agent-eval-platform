package io.agenteval.evaluation;
import io.agenteval.catalog.*;
import io.agenteval.identity.WorkspaceActor;
import io.agenteval.telemetry.EvidenceService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.util.*;
public final class ReevaluationService {
    private final ReevaluationRepository repository;
    private final CatalogService catalog;
    private final EvidenceService evidence;
    private final Clock clock;
    public ReevaluationService(ReevaluationRepository repository,CatalogService catalog,EvidenceService evidence,Clock clock) { this.repository=repository; this.catalog=catalog; this.evidence=evidence; this.clock=clock; }
    public UUID create(WorkspaceActor actor,UUID trial,UUID suiteVersion,boolean includeLate,String key) {
        if(trial==null || suiteVersion==null || key==null || !key.matches("[A-Za-z0-9_:-]{1,128}")) throw new IllegalArgumentException("Invalid reevaluation request");
        var suite=catalog.version(actor,suiteVersion); if(suite.kind()!=ResourceKind.SUITE) throw new IllegalArgumentException("Published suite required");
        var config=JsonNodeFactory.instance.objectNode().put("suite_version_id",suiteVersion.toString()).put("include_late",includeLate); config.set("suite",suite.content());
        long bytes=config.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+128L;
        if(bytes>8*1024*1024) throw new IllegalArgumentException("Reevaluation configuration exceeds limit");
        var definitions=config.putArray("evaluators"); var seen=new HashSet<UUID>();
        for(var entry:suite.content().path("entries")) {
            UUID version=UUID.fromString(entry.path("evaluator_version").asText()); if(!seen.add(version)) continue;
            var evaluator=catalog.version(actor,version); if(evaluator.kind()!=ResourceKind.EVALUATOR) throw new IllegalArgumentException("Published evaluator required");
            var content=evaluator.content(); bytes+=content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+128L;
            if(bytes>8*1024*1024) throw new IllegalArgumentException("Reevaluation configuration exceeds limit");
            definitions.addObject().put("id",version.toString()).set("content",content);
        }
        return repository.create(actor,trial,suiteVersion,includeLate,key,config,()->evidence.freezeForEvaluation(actor,trial,includeLate),clock.instant());
    }
}
