package io.agenteval.agents;
import io.agenteval.identity.WorkspaceActor;
import java.util.List;
import java.util.UUID;
public interface AgentRepository {
    AgentRecord create(WorkspaceActor actor,String name,String version);
    AgentRecord get(WorkspaceActor actor,UUID id);
    List<AgentRecord> list(WorkspaceActor actor);
}
