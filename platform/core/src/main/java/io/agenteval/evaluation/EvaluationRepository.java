package io.agenteval.evaluation;
import io.agenteval.protocol.CriterionResult;
import java.time.Instant;
import java.util.Optional;
public interface EvaluationRepository {
    int schedule(int limit,Instant now);
    Optional<EvaluatorJob> claim(Instant now);
    boolean active(EvaluatorJob job,Instant now);
    boolean defer(EvaluatorJob job,CriterionResult pending,String reason,Instant now,Instant retryAt);
    boolean complete(EvaluatorJob job,CriterionResult result,Instant now);
}
