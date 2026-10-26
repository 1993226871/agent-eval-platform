package io.agenteval.experiments;

import io.agenteval.identity.WorkspaceActor;
import io.agenteval.telemetry.ArtifactStore;
import java.time.Clock;
import java.util.*;

public final class ExperimentDeletionService {
    private final ExperimentDeletionRepository repository;
    private final ArtifactStore store;
    private final Clock clock;
    public ExperimentDeletionService(ExperimentDeletionRepository repository, ArtifactStore store, Clock clock) { this.repository=repository; this.store=store; this.clock=clock; }
    public void deleteExperiment(WorkspaceActor actor, UUID experiment, UUID idempotencyKey) {
        repository.request(Objects.requireNonNull(actor),Objects.requireNonNull(experiment),Objects.requireNonNull(idempotencyKey),clock.instant());
    }
    public ExperimentDeletionStatus status(WorkspaceActor actor,UUID experiment) { return repository.status(actor,experiment); }
    public boolean cleanup(UUID experiment) {
        var pending=repository.claim(experiment,clock.instant()); if(pending.isEmpty()) return false;
        var cleanup=pending.get();
        try {
            for(var object:cleanup.objects()) {
                String suffix=cleanup.workspaceId()+"/"+object.artifactId();
                if(!object.stagingKey().equals("staging/"+suffix) || !object.sealedKey().equals("sealed/"+suffix)) throw new IllegalStateException("Untrusted cleanup object identity");
                store.deleteObject(object.stagingKey()); store.deleteObject(object.sealedKey());
            }
            return repository.finish(cleanup,clock.instant());
        } catch(RuntimeException failure) { repository.failed(cleanup,clock.instant()); throw failure; }
    }
    public int cleanupReady(int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("Invalid cleanup batch");
        int completed=0; for(var id:repository.ready(clock.instant(),limit)) if(cleanup(id)) completed++; return completed;
    }
}
