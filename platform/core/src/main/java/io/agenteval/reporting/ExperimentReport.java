package io.agenteval.reporting;
import io.agenteval.evaluation.EvaluationAggregator.DimensionScore;
import java.math.*;
import java.util.*;
public record ExperimentReport(UUID experimentId,UUID datasetVersionId,UUID suiteVersionId,String agentVersion,String experimentStatus,
    int plannedAttempts,int startedAttempts,int finishedAttempts,int determinableAttempts,int passedAttempts,int failedAttempts,int undeterminedAttempts,int pendingAttempts,int cancelledAttempts,
    Rate passRatePlanned,Rate passRateDeterminable,Map<String,Integer> executionCounts,Map<String,DimensionScore> dimensions,Efficiency efficiency,List<UUID> evaluationSuiteVersionIds) {
    public record Rate(int numerator,int denominator,BigDecimal value) { public static Rate of(int numerator,int denominator) { return new Rate(numerator,denominator,denominator==0?null:BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator),6,RoundingMode.HALF_UP)); } }
    public record Efficiency(int observedDurations,Long p50DurationMs,Long p95DurationMs,String source) {}
}
