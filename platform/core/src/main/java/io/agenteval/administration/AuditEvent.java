package io.agenteval.administration;
import java.time.Instant;
import java.util.UUID;
public record AuditEvent(UUID id,UUID workspaceId,UUID actorUserId,String action,UUID entityId,String source,Instant createdAt) {}
