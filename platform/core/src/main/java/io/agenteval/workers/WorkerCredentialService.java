package io.agenteval.workers;
import io.agenteval.agents.AgentService;
import io.agenteval.identity.WorkspaceActor;
import java.util.*;
public final class WorkerCredentialService {
    private final CredentialRepository repository;
    private final AgentService agents;
    public WorkerCredentialService(CredentialRepository repository,AgentService agents) { this.repository=repository; this.agents=agents; }
    public IssuedCredential issue(WorkspaceActor actor,Set<UUID> agentIds) {
        if(agentIds==null || agentIds.isEmpty() || agentIds.size()>100) throw new IllegalArgumentException("Agent scope required");
        agentIds.forEach(id -> agents.get(actor,id)); UUID id=UUID.randomUUID(); String secret="ae_"+TokenSecrets.generate();
        repository.issue(actor,id,TokenSecrets.hash(secret),Set.copyOf(agentIds)); return new IssuedCredential(id,secret);
    }
    public WorkerPrincipal authenticate(String secret) {
        if(secret==null || !secret.matches("ae_[A-Za-z0-9_-]{43}")) throw new InvalidWorkerCredentialException();
        return repository.authenticate(TokenSecrets.hash(secret));
    }
    public void revoke(WorkspaceActor actor,UUID id) { repository.revoke(actor,id); }
    public List<CredentialSummary> list(WorkspaceActor actor,int offset,int limit) {
        if(offset<0 || limit<1 || limit>100) throw new IllegalArgumentException("Invalid credential page");
        return repository.list(actor,offset,limit);
    }
}
