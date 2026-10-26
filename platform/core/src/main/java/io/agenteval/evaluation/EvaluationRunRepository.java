package io.agenteval.evaluation;
import io.agenteval.identity.WorkspaceActor;
import java.util.UUID;
public interface EvaluationRunRepository { EvaluationRunView get(WorkspaceActor actor,UUID runId); }
