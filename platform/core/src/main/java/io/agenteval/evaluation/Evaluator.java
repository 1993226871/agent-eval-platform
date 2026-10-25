package io.agenteval.evaluation;
import io.agenteval.protocol.*;
public interface Evaluator { CriterionResult evaluate(EvaluatorDefinition definition,EvaluationContext context); }
