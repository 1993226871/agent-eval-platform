package io.agenteval.evaluation;
import io.agenteval.protocol.CriterionResult;
import java.time.Instant;
import java.util.*;
public record EvaluationRunView(UUID runId,UUID trialId,UUID snapshotId,String snapshotHash,UUID suiteVersionId,String purpose,String status,List<Criterion> criteria,Instant createdAt) {
    public EvaluationRunView { criteria=List.copyOf(criteria); }
    public record Criterion(UUID resultId,CriterionResult result) {}
}
