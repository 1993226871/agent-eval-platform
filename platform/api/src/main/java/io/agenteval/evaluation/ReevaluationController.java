package io.agenteval.evaluation;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/trial-evaluations/{trialId}")
public class ReevaluationController {
    private final ReevaluationService service;
    private final CurrentActor current;
    public ReevaluationController(ReevaluationService service,CurrentActor current) { this.service=service; this.current=current; }
    public record Command(UUID suiteVersionId,boolean includeLate,String idempotencyKey) {}
    public record Created(UUID runId) {}
    @PostMapping("/reevaluations") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Created create(Authentication auth,@PathVariable UUID trialId,@RequestBody Command command) { return new Created(service.create(current.require(auth),trialId,command.suiteVersionId(),command.includeLate(),command.idempotencyKey())); }
}
