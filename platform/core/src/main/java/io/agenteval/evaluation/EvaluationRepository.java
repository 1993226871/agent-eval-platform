package io.agenteval.evaluation;
import io.agenteval.protocol.CriterionResult;
import java.time.Instant;
import java.util.Optional;
public interface EvaluationRepository {
    int schedule(int limit,Instant now);
    Optional<EvaluatorJob> claim(Instant now);
    boolean complete(EvaluatorJob job,CriterionResult result,Instant now);
}
