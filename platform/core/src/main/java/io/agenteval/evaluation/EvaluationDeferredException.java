package io.agenteval.evaluation;
/** No final quality result exists yet. Orchestration must retry the same job/call identity. */
public final class EvaluationDeferredException extends RuntimeException {
    public EvaluationDeferredException(String code) { super(code); }
}
