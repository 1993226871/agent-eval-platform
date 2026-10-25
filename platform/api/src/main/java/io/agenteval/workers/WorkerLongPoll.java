package io.agenteval.workers;
import io.agenteval.catalog.CatalogConflictException;
import io.agenteval.experiments.LeaseService;
import io.agenteval.protocol.*;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded non-blocking HTTP long polls; DB remains the durable queue. */
@Component
public final class WorkerLongPoll {
    private final LeaseService leases;
    private final ConcurrentMap<UUID,DeferredResult<ResponseEntity<LeaseGrant>>> pending=new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler=new ScheduledThreadPoolExecutor(2,r -> { var t=new Thread(r,"worker-long-poll"); t.setDaemon(true); return t; });
    public WorkerLongPoll(LeaseService leases) { this.leases=leases; scheduler.setRemoveOnCancelPolicy(true); }
    public DeferredResult<ResponseEntity<LeaseGrant>> poll(WorkerPrincipal p,ClaimRequest request) {
        var immediate=leases.claim(p,request);
        var result=new DeferredResult<ResponseEntity<LeaseGrant>>(request.maxWaitSeconds()*1000L);
        if(immediate.isPresent()) { result.setResult(ResponseEntity.ok(immediate.get())); return result; }
        if(request.maxWaitSeconds()==0) { result.setResult(ResponseEntity.noContent().build()); return result; }
        if(pending.putIfAbsent(request.workerId(),result)!=null) throw new CatalogConflictException();
        var future=new AtomicReference<ScheduledFuture<?>>();
        Runnable cleanup=() -> { pending.remove(request.workerId(),result); var job=future.get(); if(job!=null) job.cancel(false); };
        result.onCompletion(cleanup);
        result.onTimeout(() -> { result.setResult(ResponseEntity.noContent().build()); cleanup.run(); });
        result.onError(error -> cleanup.run());
        future.set(scheduler.scheduleWithFixedDelay(() -> {
            if(result.isSetOrExpired()) { cleanup.run(); return; }
            try { leases.claim(p,request).ifPresent(grant -> result.setResult(ResponseEntity.ok(grant))); }
            catch(RuntimeException failure) { result.setErrorResult(failure); cleanup.run(); }
        },0,200,TimeUnit.MILLISECONDS));
        return result;
    }
    @PreDestroy public void close() { pending.values().forEach(result -> result.setResult(ResponseEntity.noContent().build())); scheduler.shutdownNow(); pending.clear(); }
}
