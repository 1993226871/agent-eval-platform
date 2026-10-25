package io.agenteval.protocol;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CriterionResult(String criterionId, CriterionStatus status, BigDecimal score,
    Boolean passed, String reason, List<String> evidenceRefs, UUID evaluatorVersion) {
    public CriterionResult {
        Checks.text(criterionId,"criterion_id"); Checks.required(status,"status"); Checks.text(reason,"reason");
        Checks.required(evaluatorVersion,"evaluator_version"); evidenceRefs = List.copyOf(evidenceRefs);
        if (status == CriterionStatus.SCORED) {
            if (score == null || score.compareTo(BigDecimal.ZERO)<0 || score.compareTo(BigDecimal.ONE)>0 || passed==null)
                throw new IllegalArgumentException("Scored result requires score in [0,1] and passed");
        } else if (score!=null || passed!=null) throw new IllegalArgumentException("Unscored result cannot carry score or passed");
    }
}
