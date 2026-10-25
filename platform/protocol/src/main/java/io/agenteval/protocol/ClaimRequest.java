package io.agenteval.protocol;
import java.util.UUID;

public record ClaimRequest(UUID workerId, UUID agentId, String agentVersion, int maxWaitSeconds) {
    public ClaimRequest {
        Checks.required(workerId,"worker_id"); Checks.required(agentId,"agent_id"); Checks.text(agentVersion,"agent_version");
        if(maxWaitSeconds<0 || maxWaitSeconds>20) throw new IllegalArgumentException("max_wait_seconds must be in [0,20]");
    }
}
