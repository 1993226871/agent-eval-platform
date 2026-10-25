package io.agenteval.agents;
import io.agenteval.identity.WorkspaceActor;
import java.util.*;
public final class AgentService {
    private final AgentRepository repository;
    public AgentService(AgentRepository repository) { this.repository=repository; }
    public AgentRecord create(WorkspaceActor actor,String name,String version) {
        if(name==null || name.isBlank() || name.length()>200 || version==null || version.isBlank() || version.length()>128) throw new IllegalArgumentException("Agent name/version required");
        return repository.create(actor,name.strip(),version.strip());
    }
    public AgentRecord get(WorkspaceActor actor,UUID id) { return repository.get(actor,id); }
    public List<AgentRecord> list(WorkspaceActor actor) { return repository.list(actor); }
}
