package io.agenteval.telemetry;
import io.agenteval.identity.WorkspaceActor;
import io.agenteval.protocol.TraceEvent;
import io.agenteval.workers.WorkerPrincipal;
import java.time.Instant;
import java.util.*;
public interface TelemetryRepository {
    BatchAck ingest(WorkerPrincipal principal,UUID trialId,String token,List<TraceEvent> events,Instant now);
    TracePage query(WorkspaceActor actor,UUID trialId,String cursor,int limit);
}
