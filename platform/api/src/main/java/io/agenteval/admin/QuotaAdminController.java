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
    private final CnyQuotaService money;
    public QuotaAdminController(QuotaService quota,CurrentActor current,CnyQuotaService money) { this.quota=quota; this.current=current; this.money=money; }
    public record MoneyAdjustment(String amount,String reason,UUID idempotencyKey) {}
    @PostMapping("/money-adjustments") public QuotaController.WalletView money(Authentication auth,@PathVariable UUID workspaceId,@RequestBody MoneyAdjustment body) {
        return QuotaController.WalletView.of(money.adjust(current.require(auth),workspaceId,body.amount(),body.reason(),body.idempotencyKey()));
    }
    public record Adjustment(String credits,String reason,UUID idempotencyKey) {}
    @PostMapping("/adjustments") public QuotaController.WalletView adjust(Authentication auth,@PathVariable UUID workspaceId,@RequestBody Adjustment body) {
        if(body.credits()==null || !body.credits().matches("-?(0|[1-9][0-9]{0,18})")) throw new IllegalArgumentException("Integer credit amount required");
        return QuotaController.WalletView.of(quota.grant(current.require(auth),workspaceId,Long.parseLong(body.credits()),body.reason(),body.idempotencyKey()));
    }
}
