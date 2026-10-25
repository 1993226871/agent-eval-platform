package io.agenteval.evaluation;
import io.agenteval.protocol.*;
import io.agenteval.telemetry.EvidenceService;
import java.time.Clock;
import java.util.List;
public final class EvaluationOrchestrator {
    private final EvaluationRepository repository;
    private final EvidenceService evidence;
    private final Evaluator rules;
    private final Clock clock;
    public EvaluationOrchestrator(EvaluationRepository repository,EvidenceService evidence,Evaluator rules,Clock clock) { this.repository=repository; this.evidence=evidence; this.rules=rules; this.clock=clock; }
    public int schedule(int limit) { if(limit<1 || limit>200) throw new IllegalArgumentException("Invalid scheduling limit"); return repository.schedule(limit,clock.instant()); }
    public boolean runNext() {
        var candidate=repository.claim(clock.instant()); if(candidate.isEmpty()) return false; var job=candidate.get();
        var context=evidence.context(job.owner(),job.snapshotId()); CriterionResult result;
        if(job.definition().type().equals("RULE")) result=rules.evaluate(job.definition(),context);
        else result=new CriterionResult(job.definition().criterionId(),CriterionStatus.SKIPPED,null,null,"Evaluator capability is not enabled",List.of(),job.definition().evaluatorVersion());
        new EvidenceReferenceValidator().validate(context,result.evidenceRefs()); repository.complete(job,result,clock.instant()); return true;
    }
}
