package io.agenteval.identity;
import java.util.UUID;
/** Internal account model. Never return this record from HTTP handlers. */
public record UserAccount(UUID userId,UUID workspaceId,String email,String passwordHash,boolean mustChangePassword,boolean admin) {
    public WorkspaceActor actor() { return new WorkspaceActor(userId,workspaceId,admin); }
}
