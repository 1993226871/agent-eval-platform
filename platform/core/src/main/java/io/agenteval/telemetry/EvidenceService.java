package io.agenteval.telemetry;
import io.agenteval.identity.WorkspaceActor;
import io.agenteval.protocol.*;
import java.time.Clock;
import java.util.*;
public final class EvidenceService {
    private final EvidenceRepository repository;
    private final Clock clock;
    private final ProtocolCodec codec=new ProtocolCodec();
    public EvidenceService(EvidenceRepository repository,Clock clock) { this.repository=repository; this.clock=clock; }
    public EvidenceSnapshot freeze(WorkspaceActor actor,UUID trial,boolean includeLate) { return repository.freeze(actor,trial,includeLate,clock.instant()); }
    public EvidenceSnapshot load(WorkspaceActor actor,UUID id) { return repository.load(actor,id); }
    public int freezeReady(int limit) { if(limit<1 || limit>200) throw new IllegalArgumentException(); return repository.freezeReady(limit,clock.instant()); }
    public EvaluationContext context(WorkspaceActor actor,UUID id) {
        var snapshot=load(actor,id); var m=snapshot.manifest();
        var events=new ArrayList<TraceEvent>(); var artifacts=new ArrayList<ArtifactRef>();
        try {
            for(var event:m.path("events")) events.add(codec.decode(event.toString(),TraceEvent.class));
            for(var artifact:m.path("artifacts")) artifacts.add(codec.decode(artifact.toString(),ArtifactRef.class));
        } catch(java.io.IOException invalid) { throw new IllegalStateException("Invalid stored evidence",invalid); }
        return new EvaluationContext(snapshot.trialId(),id,m.path("task").path("input"),m.path("task").path("expected"),m.path("completion").path("output"),events,artifacts,snapshot.completeness());
    }
}
