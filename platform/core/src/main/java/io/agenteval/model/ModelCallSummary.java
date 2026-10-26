package io.agenteval.model;
import java.time.Instant;
import java.util.UUID;
/** Operational reconciliation metadata; excludes user prompts and generated content. */
public record ModelCallSummary(UUID callId,UUID workspaceId,UUID profileId,UUID priceVersionId,String status,long reservedCredits,Long inputTokens,Long outputTokens,Long reportedCredits,String failureCode,Instant createdAt) {}
