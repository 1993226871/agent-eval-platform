package io.agenteval.telemetry;
import io.agenteval.identity.WorkspaceActor;
import io.agenteval.protocol.*;
import io.agenteval.workers.WorkerPrincipal;
import java.time.Clock;
import java.util.*;
import java.nio.charset.StandardCharsets;

public final class TelemetryService {
    private final TelemetryRepository repository;
    private final Clock clock;
    private final ProtocolCodec codec=new ProtocolCodec();
    public TelemetryService(TelemetryRepository repository,Clock clock) { this.repository=repository; this.clock=clock; }
    public BatchAck ingest(WorkerPrincipal principal,UUID trialId,String token,List<TraceEvent> events) {
        if(events==null || events.isEmpty() || events.size()>100) throw new TelemetryLimitException();
        long total=0;
        for(var event:events) {
            if(!trialId.equals(event.trialId())) throw new IllegalArgumentException("Trial ID mismatch");
            if(event.payload().toString().getBytes(StandardCharsets.UTF_8).length>65536) throw new TelemetryLimitException();
            try { total+=codec.encode(event).getBytes(StandardCharsets.UTF_8).length; } catch(java.io.IOException invalid) { throw new IllegalArgumentException("Invalid event",invalid); }
        }
        if(total>1024*1024) throw new TelemetryLimitException();
        return repository.ingest(principal,trialId,token,List.copyOf(events),clock.instant());
    }
    public TracePage queryTrialTrace(WorkspaceActor actor,UUID trialId,String cursor,int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("Invalid page size"); return repository.query(actor,trialId,cursor,limit);
    }
}
