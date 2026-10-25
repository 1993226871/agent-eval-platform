package io.agenteval.quota;
import java.util.UUID;
public record Reservation(UUID callId,UUID workspaceId,long upperCredits,String state,Long actualCredits) {}
