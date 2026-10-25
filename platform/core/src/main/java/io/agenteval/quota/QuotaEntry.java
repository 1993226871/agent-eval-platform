package io.agenteval.quota;
import java.time.Instant;
import java.util.UUID;
public record QuotaEntry(UUID id,UUID callId,String type,long grantedDelta,long consumedDelta,long reservedDelta,String reason,UUID actorUserId,Instant createdAt) {}
