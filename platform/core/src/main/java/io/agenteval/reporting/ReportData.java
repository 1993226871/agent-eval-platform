package io.agenteval.reporting;
import io.agenteval.evaluation.EvaluationEntry;
import io.agenteval.protocol.*;
import java.time.Instant;
import java.util.*;
public record ReportData(UUID experimentId,UUID datasetVersionId,UUID suiteVersionId,String agentVersion,String experimentStatus,List<EvaluationEntry> entries,List<TrialEvaluation> trials) {
    public ReportData { entries=List.copyOf(entries); trials=List.copyOf(trials); }
    public record TrialEvaluation(UUID trialId,int caseIndex,int attempt,TrialExecutionStatus executionStatus,Instant startedAt,UUID runId,UUID snapshotId,String evaluationState,Map<UUID,CriterionResult> results,Long durationMs,UUID suiteVersionId,List<EvaluationEntry> entries,String evidenceWaitReason) { public TrialEvaluation { results=Map.copyOf(results); entries=List.copyOf(entries); } }
}
