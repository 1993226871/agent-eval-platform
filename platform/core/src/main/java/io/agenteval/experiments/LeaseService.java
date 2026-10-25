package io.agenteval.experiments;
import io.agenteval.protocol.*;
import io.agenteval.workers.WorkerPrincipal;
import java.time.Clock;
import java.util.*;
public final class LeaseService {
    private final LeaseRepository repository;
    private final Clock clock;
    public LeaseService(LeaseRepository repository,Clock clock) { this.repository=repository; this.clock=clock; }
    public Optional<LeaseGrant> claim(WorkerPrincipal p,ClaimRequest r) { return repository.claim(p,r,clock.instant()); }
    public AttemptContext validateAttempt(WorkerPrincipal p,UUID trial,String token) { return repository.validateAttempt(p,trial,token,clock.instant()); }
    public void started(WorkerPrincipal p,UUID trial,String token) { repository.started(p,trial,token,clock.instant()); }
    public HeartbeatReply heartbeat(WorkerPrincipal p,UUID trial,String token) { return repository.heartbeat(p,trial,token,clock.instant()); }
    public void complete(WorkerPrincipal p,UUID trial,String token,CompletionRequest r) { repository.complete(p,trial,token,r,clock.instant()); }
}
