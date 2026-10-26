package io.agenteval.reporting;
import io.agenteval.evaluation.EvaluationAggregator.DimensionScore;
import io.agenteval.protocol.*;
import java.util.*;
public record TrialReport(UUID trialId,int caseIndex,int attempt,TrialExecutionStatus executionStatus,UUID runId,UUID snapshotId,String evaluationState,String verdict,Map<String,DimensionScore> dimensions,List<CriterionResult> criteria,Long reportedDurationMs,UUID suiteVersionId) {
    public TrialReport { criteria=List.copyOf(criteria); dimensions=Map.copyOf(dimensions); }
}
