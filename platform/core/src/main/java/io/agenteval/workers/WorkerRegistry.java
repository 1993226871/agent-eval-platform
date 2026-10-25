package io.agenteval.workers;
import io.agenteval.identity.ScopeViolationException;
import io.agenteval.protocol.RegisterWorkerRequest;
import io.agenteval.protocol.WorkerRegistration;
import java.time.*;
public final class WorkerRegistry {
    public interface Repository { WorkerRegistration register(WorkerPrincipal principal,RegisterWorkerRequest request,Instant now); }
    private final Repository repository;
    private final Clock clock;
    public WorkerRegistry(Repository repository,Clock clock) { this.repository=repository; this.clock=clock; }
    public WorkerRegistration register(WorkerPrincipal principal,RegisterWorkerRequest request) {
        if(!principal.agentIds().contains(request.agentId())) throw new ScopeViolationException();
        return repository.register(principal,request,clock.instant());
    }
}
