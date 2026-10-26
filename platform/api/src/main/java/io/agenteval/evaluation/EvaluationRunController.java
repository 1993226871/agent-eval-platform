package io.agenteval.evaluation;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1")
public class EvaluationRunController {
    private final EvaluationRunService runs;
    private final EvaluatorPreviewService previews;
    private final CurrentActor current;
    public EvaluationRunController(EvaluationRunService runs,EvaluatorPreviewService previews,CurrentActor current) { this.runs=runs; this.previews=previews; this.current=current; }
    @GetMapping("/evaluation-runs/{runId}") public EvaluationRunView get(Authentication auth,@PathVariable UUID runId) { return runs.get(current.require(auth),runId); }
    public record Preview(long revision,UUID trialId,String idempotencyKey) {}
    @PostMapping("/evaluators/{draftId}/previews") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ReevaluationController.Created preview(Authentication auth,@PathVariable UUID draftId,@RequestBody Preview command) { return new ReevaluationController.Created(previews.create(current.require(auth),draftId,command.revision(),command.trialId(),command.idempotencyKey())); }
}
