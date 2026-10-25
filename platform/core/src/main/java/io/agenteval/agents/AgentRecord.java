package io.agenteval.agents;
import java.util.UUID;
public record AgentRecord(UUID id,UUID workspaceId,String name,String version) {}
