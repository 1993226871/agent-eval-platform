package io.agenteval.evaluation;
import io.agenteval.protocol.*;
public interface Evaluator {
    CriterionResult evaluate(EvaluatorDefinition definition,EvaluationContext context);
    default CriterionResult evaluate(EvaluatorJob job,EvaluationContext context) { return evaluate(job.definition(),context); }
}
