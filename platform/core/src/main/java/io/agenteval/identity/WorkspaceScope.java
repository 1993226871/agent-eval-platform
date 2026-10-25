package io.agenteval.identity;
import java.util.UUID;
public final class WorkspaceScope {
    private WorkspaceScope() {}
    public static void require(WorkspaceActor actor,UUID resourceWorkspaceId) {
        if(actor==null || !actor.workspaceId().equals(resourceWorkspaceId)) throw new ScopeViolationException();
    }
}
