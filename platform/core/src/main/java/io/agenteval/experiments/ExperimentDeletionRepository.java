package io.agenteval.experiments;

import io.agenteval.identity.WorkspaceActor;
import java.time.Instant;
import java.util.*;

public interface ExperimentDeletionRepository {
    void request(WorkspaceActor actor, UUID experiment, UUID key, Instant now);
    ExperimentDeletionStatus status(WorkspaceActor actor,UUID experiment);
    Optional<Cleanup> claim(UUID experiment, Instant now);
    boolean finish(Cleanup cleanup, Instant now);
    void failed(Cleanup cleanup, Instant now);
    List<UUID> ready(Instant now, int limit);
    record ObjectTarget(UUID artifactId, String stagingKey, String sealedKey) {}
    record Cleanup(UUID experimentId, UUID workspaceId, UUID generation, UUID token, List<ObjectTarget> objects) {
        public Cleanup { objects=List.copyOf(objects); }
    }
}
