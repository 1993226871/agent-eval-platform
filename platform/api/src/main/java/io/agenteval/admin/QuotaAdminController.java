package io.agenteval.admin;
import io.agenteval.quota.*;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/workspaces/{workspaceId}/quota")
public class QuotaAdminController {
    private final QuotaService quota;
    private final CurrentActor current;
    public QuotaAdminController(QuotaService quota,CurrentActor current) { this.quota=quota; this.current=current; }
    public record Adjustment(long credits,String reason,UUID idempotencyKey) {}
    @PostMapping("/adjustments") public QuotaController.WalletView adjust(Authentication auth,@PathVariable UUID workspaceId,@RequestBody Adjustment body) {
        return QuotaController.WalletView.of(quota.grant(current.require(auth),workspaceId,body.credits(),body.reason(),body.idempotencyKey()));
    }
}
