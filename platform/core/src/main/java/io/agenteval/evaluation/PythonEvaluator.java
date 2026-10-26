package io.agenteval.evaluation;
import io.agenteval.protocol.*;
import java.util.*;
public final class PythonEvaluator implements Evaluator {
    private final SandboxClient sandbox;
    public PythonEvaluator(SandboxClient sandbox) { this.sandbox=sandbox; }
    public CriterionResult evaluate(EvaluatorDefinition definition,EvaluationContext context) { return result(definition,CriterionStatus.ERROR,"Python evaluation requires a durable job"); }
    public CriterionResult evaluate(EvaluatorJob job,EvaluationContext context) {
        if(!job.trialId().equals(context.trialId()) || !job.snapshotId().equals(context.snapshotId())) throw new IllegalArgumentException("Foreign Python evidence");
        for(String required:job.definition().requiredEvidence()) {
            boolean missing=switch(required) {
                case "output" -> context.output().isMissingNode(); case "expected" -> context.expected().isMissingNode();
                case "task_input" -> context.taskInput().isMissingNode(); case "task_instruction" -> context.taskInstruction().isBlank();
                case "trace" -> context.completeness()!=EvidenceCompleteness.COMPLETE; case "events" -> context.events().isEmpty(); case "artifacts" -> context.artifacts().isEmpty();
                default -> throw new IllegalArgumentException("Unknown required Python evidence");
            };
            if(missing) return result(job.definition(),CriterionStatus.INSUFFICIENT_EVIDENCE,"REQUIRED_PYTHON_EVIDENCE_MISSING");
        }
        try { return sandbox.evaluate(job,context); }
        catch(SandboxClient.RetryableException pending) { throw new EvaluationDeferredException("PYTHON_RUN_PENDING"); }
    }
    private CriterionResult result(EvaluatorDefinition definition,CriterionStatus status,String reason) { return new CriterionResult(definition.criterionId(),status,null,null,reason,List.of(),definition.evaluatorVersion()); }
}
