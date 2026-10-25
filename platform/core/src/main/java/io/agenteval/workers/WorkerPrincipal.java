package io.agenteval.workers;
import java.util.Set;
import java.util.UUID;
public record WorkerPrincipal(UUID workspaceId,Set<UUID> agentIds,UUID credentialId) {
    public WorkerPrincipal { agentIds=Set.copyOf(agentIds); }
}
