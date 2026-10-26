package io.agenteval.experiments;
import java.time.Instant;
import java.util.UUID;
public record ExperimentDeletionStatus(UUID experimentId,String status,Instant requestedAt,Instant nextCleanupAt,Instant completedAt,int failureCount) {}
