package io.agenteval.evaluation;

import io.agenteval.protocol.CriterionResult;
import io.agenteval.protocol.EvaluationContext;

public interface SandboxClient {
    CriterionResult evaluate(EvaluatorJob job, EvaluationContext context);

    /** Retry with the same job ID; a lost response must never create a second execution. */
    final class RetryableException extends RuntimeException {
        public RetryableException(String message) { super(message); }
    }
}
