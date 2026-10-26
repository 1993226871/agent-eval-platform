package io.agenteval.evaluation;
import io.agenteval.identity.WorkspaceActor;
import java.util.UUID;
public final class EvaluationRunService {
    private final EvaluationRunRepository repository;
    public EvaluationRunService(EvaluationRunRepository repository) { this.repository=repository; }
    public EvaluationRunView get(WorkspaceActor actor,UUID id) { if(id==null) throw new IllegalArgumentException("Run required"); return repository.get(actor,id); }
}
