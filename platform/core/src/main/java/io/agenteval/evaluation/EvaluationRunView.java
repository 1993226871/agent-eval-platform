package io.agenteval.evaluation;
import io.agenteval.protocol.CriterionResult;
import java.time.Instant;
import java.util.*;
public record EvaluationRunView(UUID runId,UUID trialId,UUID snapshotId,String snapshotHash,UUID suiteVersionId,String purpose,String status,List<Criterion> criteria,Instant createdAt,List<String> waitReasons) {
    public EvaluationRunView { criteria=List.copyOf(criteria); waitReasons=List.copyOf(waitReasons); }
    public EvaluationRunView(UUID runId,UUID trialId,UUID snapshotId,String snapshotHash,UUID suiteVersionId,String purpose,String status,List<Criterion> criteria,Instant createdAt) { this(runId,trialId,snapshotId,snapshotHash,suiteVersionId,purpose,status,criteria,createdAt,List.of()); }
    public record Criterion(UUID resultId,CriterionResult result) {}
}
