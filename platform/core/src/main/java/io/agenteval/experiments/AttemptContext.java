package io.agenteval.experiments;
import io.agenteval.protocol.TrialExecutionStatus;
import java.time.Instant;
import java.util.UUID;
public record AttemptContext(UUID workspaceId,UUID trialId,UUID workerId,TrialExecutionStatus status,Instant leaseExpiresAt,Instant deadline,String experimentStatus) {}
