package io.agenteval.workers;
import java.time.Instant;
import java.util.*;
public record CredentialSummary(UUID id,Set<UUID> agentIds,boolean revoked,Instant createdAt) {
    public CredentialSummary { agentIds=Set.copyOf(agentIds); }
}
