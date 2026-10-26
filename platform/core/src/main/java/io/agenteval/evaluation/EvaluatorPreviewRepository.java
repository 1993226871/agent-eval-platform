package io.agenteval.evaluation;
import io.agenteval.identity.WorkspaceActor;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
public interface EvaluatorPreviewRepository {
    UUID create(WorkspaceActor actor,UUID draftId,long revision,UUID trial,String key,Supplier<UUID> run,Instant now);
}
