package io.agenteval.workers;
import io.agenteval.experiments.*;
import io.agenteval.protocol.*;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class WorkerController {
    private final WorkerRegistry registry;
    private final LeaseService leases;
    private final WorkerLongPoll polls;
    public WorkerController(WorkerRegistry registry,LeaseService leases,WorkerLongPoll polls) { this.registry=registry; this.leases=leases; this.polls=polls; }
    @PostMapping("/workers/register") public WorkerRegistration register(Authentication auth,@RequestBody RegisterWorkerRequest request) { return registry.register(principal(auth),request); }
    @PostMapping("/workers/{id}/claim") public org.springframework.web.context.request.async.DeferredResult<ResponseEntity<LeaseGrant>> claim(Authentication auth,@PathVariable UUID id,@RequestBody ClaimRequest request) {
        if(!id.equals(request.workerId())) throw new IllegalArgumentException("Worker ID mismatch");
        return polls.poll(principal(auth),request);
    }
    @PostMapping("/trials/{id}/started") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void started(Authentication auth,@PathVariable UUID id,@RequestHeader("X-Attempt-Token") String token) { leases.started(principal(auth),id,token); }
    @PostMapping("/trials/{id}/heartbeat") public HeartbeatReply heartbeat(Authentication auth,@PathVariable UUID id,@RequestHeader("X-Attempt-Token") String token) { return leases.heartbeat(principal(auth),id,token); }
    @PostMapping("/trials/{id}/complete") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void complete(Authentication auth,@PathVariable UUID id,@RequestHeader("X-Attempt-Token") String token,@RequestBody CompletionRequest request) { leases.complete(principal(auth),id,token,request); }
    static WorkerPrincipal principal(Authentication auth) {
        if(auth==null || !(auth.getPrincipal() instanceof WorkerPrincipal principal)) throw new InvalidWorkerCredentialException(); return principal;
    }
}
