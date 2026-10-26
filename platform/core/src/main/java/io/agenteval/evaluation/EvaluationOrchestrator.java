package io.agenteval.evaluation;
import io.agenteval.protocol.*;
import io.agenteval.telemetry.EvidenceService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
public final class EvaluationOrchestrator {
    private final EvaluationRepository repository;
    private final EvidenceService evidence;
    private final Map<String,Evaluator> evaluators;
    private final Clock clock;
    public EvaluationOrchestrator(EvaluationRepository repository,EvidenceService evidence,Evaluator rules,Clock clock) { this(repository,evidence,Map.of("RULE",rules),clock); }
    public EvaluationOrchestrator(EvaluationRepository repository,EvidenceService evidence,Map<String,Evaluator> evaluators,Clock clock) { this.repository=repository; this.evidence=evidence; this.evaluators=Map.copyOf(evaluators); this.clock=clock; }
    public int schedule(int limit) { if(limit<1 || limit>200) throw new IllegalArgumentException("Invalid scheduling limit"); return repository.schedule(limit,clock.instant()); }
    public boolean runNext() {
        var candidate=repository.claim(clock.instant()); if(candidate.isEmpty()) return false; var job=candidate.get();
        if(!repository.active(job,clock.instant())) return true;
        var context=evidence.context(job.owner(),job.snapshotId()); CriterionResult result;
        try {
            var evaluator=evaluators.get(job.definition().type());
            result=evaluator==null?new CriterionResult(job.definition().criterionId(),CriterionStatus.SKIPPED,null,null,"Evaluator capability is not enabled",List.of(),job.definition().evaluatorVersion()):evaluator.evaluate(job,context);
        } catch(EvaluationDeferredException pending) {
            var now=clock.instant(); repository.defer(job,null,pending.getMessage(),now,now.plusSeconds(pending.getMessage().contains("RECONCILIATION")?30:5)); return true;
        } catch(IllegalArgumentException invalid) {
            result=new CriterionResult(job.definition().criterionId(),CriterionStatus.ERROR,null,null,"INVALID_EVALUATOR_CONFIGURATION_OR_EVIDENCE",List.of(),job.definition().evaluatorVersion());
        }
        new EvidenceReferenceValidator().validate(context,result.evidenceRefs());
        if(result.status()==CriterionStatus.WAITING_QUOTA) { var now=clock.instant(); repository.defer(job,result,"WAITING_QUOTA",now,now.plusSeconds(5)); }
        else repository.complete(job,result,clock.instant());
        return true;
    }
}
