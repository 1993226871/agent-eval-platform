package io.agenteval.identity;
import java.util.Objects;
import java.util.UUID;
public record WorkspaceActor(UUID userId,UUID workspaceId,boolean admin) {
    public WorkspaceActor { Objects.requireNonNull(userId); Objects.requireNonNull(workspaceId); }
}
