package io.agenteval.admin;
import io.agenteval.quota.*;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/workspaces/{workspaceId}/quota")
public class QuotaAdminController {
    private final CurrentActor current;
    private final CnyQuotaService money;
    public QuotaAdminController(CurrentActor current,CnyQuotaService money) { this.current=current; this.money=money; }
    public record Adjustment(String amount,String reason,UUID idempotencyKey) {}
    @PostMapping("/adjustments") public QuotaController.WalletView adjust(Authentication auth,@PathVariable UUID workspaceId,@RequestBody Adjustment body) {
        return QuotaController.WalletView.of(money.adjust(current.require(auth),workspaceId,body.amount(),body.reason(),body.idempotencyKey()));
    }
}
