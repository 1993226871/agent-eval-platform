package io.agenteval.evaluation;
import io.agenteval.identity.WorkspaceActor;
import java.time.Instant;
import java.util.UUID;
public record EvaluatorJob(UUID id,UUID runId,UUID trialId,UUID snapshotId,WorkspaceActor owner,EvaluatorDefinition definition,UUID leaseToken,Instant leaseExpiresAt) {}
