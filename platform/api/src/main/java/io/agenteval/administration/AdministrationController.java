package io.agenteval.administration;

import io.agenteval.experiments.*;
import io.agenteval.security.CurrentActor;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AdministrationController {
    private final ResourceLimitService limits;
    private final AuditService audit;
    private final ExperimentDeletionService deletion;
    private final CurrentActor current;
    public AdministrationController(ResourceLimitService limits,AuditService audit,ExperimentDeletionService deletion,CurrentActor current) { this.limits=limits; this.audit=audit; this.deletion=deletion; this.current=current; }
    @GetMapping("/resource-limits") public ResourceLimitService.View get(Authentication auth) { return limits.get(current.require(auth)); }
    @GetMapping("/admin/workspaces/{workspaceId}/resource-limits") public ResourceLimitService.View getAdmin(Authentication auth,@PathVariable UUID workspaceId) { return limits.getForAdministrator(current.require(auth),workspaceId); }
    public record Configure(ResourceLimits limits,String reason) {}
    @PostMapping("/admin/workspaces/{workspaceId}/resource-limits") public ResourceLimitService.View configure(Authentication auth,@PathVariable UUID workspaceId,@RequestBody Configure command) {
        var actor=current.require(auth); limits.configure(actor,workspaceId,command.limits(),command.reason()); return limits.getForAdministrator(actor,workspaceId);
    }
    @GetMapping("/audit-events") public AuditService.Page audit(Authentication auth,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="25") int limit) { return audit.list(current.require(auth),cursor,limit); }
    public record Delete(UUID idempotencyKey) { public Delete { if(idempotencyKey==null) throw new IllegalArgumentException("Deletion idempotency key required"); } }
    @DeleteMapping("/experiments/{id}") @ResponseStatus(HttpStatus.ACCEPTED)
    public ExperimentDeletionStatus delete(Authentication auth,@PathVariable UUID id,@RequestBody Delete command) {
        var actor=current.require(auth); deletion.deleteExperiment(actor,id,command.idempotencyKey()); return deletion.status(actor,id);
    }
    @GetMapping("/experiment-deletions/{id}") public ExperimentDeletionStatus status(Authentication auth,@PathVariable UUID id) { return deletion.status(current.require(auth),id); }
}
