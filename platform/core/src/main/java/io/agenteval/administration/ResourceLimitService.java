package io.agenteval.administration;

import io.agenteval.identity.*;
import java.util.UUID;

public final class ResourceLimitService {
    public interface Repository {
        View get(UUID workspace);
        void configure(WorkspaceActor actor,UUID workspace,ResourceLimits limits,String reason);
    }
    public record View(ResourceLimits limits,ResourceUsage usage) {}
    private final Repository repository;
    public ResourceLimitService(Repository repository) { this.repository=repository; }
    public View get(WorkspaceActor actor) { return repository.get(actor.workspaceId()); }
    public View getForAdministrator(WorkspaceActor actor,UUID workspace) { if(!actor.admin()) throw new ScopeViolationException(); return repository.get(workspace); }
    public void configure(WorkspaceActor actor,UUID workspace,ResourceLimits limits,String reason) {
        if(!actor.admin()) throw new ScopeViolationException();
        if(workspace==null || limits==null || reason==null || reason.isBlank() || reason.length()>500) throw new IllegalArgumentException("Invalid resource configuration");
        repository.configure(actor,workspace,limits,reason.strip());
    }
}
