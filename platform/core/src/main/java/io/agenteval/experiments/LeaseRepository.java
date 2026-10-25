package io.agenteval.experiments;
import io.agenteval.protocol.*;
import io.agenteval.workers.WorkerPrincipal;
import java.time.Instant;
import java.util.*;
public interface LeaseRepository {
    Optional<LeaseGrant> claim(WorkerPrincipal principal,ClaimRequest request,Instant now);
    AttemptContext validateAttempt(WorkerPrincipal principal,UUID trialId,String token,Instant now);
    void started(WorkerPrincipal principal,UUID trialId,String token,Instant now);
    HeartbeatReply heartbeat(WorkerPrincipal principal,UUID trialId,String token,Instant now);
    void complete(WorkerPrincipal principal,UUID trialId,String token,CompletionRequest request,Instant now);
}
