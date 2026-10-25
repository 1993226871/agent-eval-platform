package io.agenteval.protocol;
import java.time.Instant;
import java.util.UUID;

public record LeaseGrant(UUID trialId,String traceId,String attemptToken,Instant leaseExpiresAt,TrialContextPayload task) {
    public LeaseGrant {
        Checks.required(trialId,"trial_id"); Checks.hexId(traceId,32,"trace_id"); Checks.text(attemptToken,"attempt_token");
        Checks.required(leaseExpiresAt,"lease_expires_at"); Checks.required(task,"task");
        if(!trialId.equals(task.trialId())) throw new IllegalArgumentException("Lease and task must identify the same trial");
    }
}
