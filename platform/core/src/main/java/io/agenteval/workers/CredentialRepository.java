package io.agenteval.workers;
import io.agenteval.identity.WorkspaceActor;
import java.util.*;
public interface CredentialRepository {
    void issue(WorkspaceActor actor,UUID id,String secretHash,Set<UUID> agentIds);
    WorkerPrincipal authenticate(String secretHash);
    void revoke(WorkspaceActor actor,UUID id);
}
