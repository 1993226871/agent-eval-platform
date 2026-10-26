package io.agenteval.evaluation;
import io.agenteval.catalog.*;
import io.agenteval.identity.WorkspaceActor;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.util.UUID;

public final class EvaluatorPreviewService {
    private final EvaluatorPreviewRepository repository;
    private final CatalogService catalog;
    private final ReevaluationService evaluations;
    private final Clock clock;
    public EvaluatorPreviewService(EvaluatorPreviewRepository repository,CatalogService catalog,ReevaluationService evaluations,Clock clock) { this.repository=repository; this.catalog=catalog; this.evaluations=evaluations; this.clock=clock; }
    public UUID create(WorkspaceActor actor,UUID draftId,long revision,UUID trial,String key) {
        if(draftId==null || trial==null || revision<1 || key==null || !key.matches("[A-Za-z0-9_:-]{1,128}")) throw new IllegalArgumentException("Invalid evaluator preview");
        return repository.create(actor,draftId,revision,trial,key,()->{
            var source=catalog.draft(actor,draftId);
            if(source.kind()!=ResourceKind.EVALUATOR || source.archived() || source.revision()!=revision) throw new CatalogConflictException();
            // Freeze a private archived copy; trying a draft must not publish or alter its source.
            String name="Preview: "+source.name(); if(name.length()>200) name=name.substring(0,200);
            var copy=catalog.create(actor,ResourceKind.EVALUATOR,name,source.content());
            var version=catalog.publish(actor,copy.id());
            var content=JsonNodeFactory.instance.objectNode(); content.putArray("entries").addObject().put("evaluator_version",version.id().toString()).put("dimension","outcome").put("required_to_pass",true).put("weight",1);
            var suite=catalog.create(actor,ResourceKind.SUITE,name,content); var suiteVersion=catalog.publish(actor,suite.id());
            catalog.archive(actor,copy.id()); catalog.archive(actor,suite.id());
            return evaluations.create(actor,trial,suiteVersion.id(),false,"preview-"+UUID.randomUUID());
        },clock.instant());
    }
}
