package io.agenteval.quota;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/quota")
public class QuotaController {
    private final QuotaService quota;
    private final CurrentActor current;
    public QuotaController(QuotaService quota,CurrentActor current) { this.quota=quota; this.current=current; }
    public record WalletView(UUID workspaceId,long granted,long consumed,long reserved,long available) {
        public static WalletView of(QuotaWallet wallet) { return new WalletView(wallet.workspaceId(),wallet.granted(),wallet.consumed(),wallet.reserved(),wallet.available()); }
    }
    @GetMapping public WalletView wallet(Authentication auth) { return WalletView.of(quota.wallet(current.require(auth))); }
    @GetMapping("/ledger") public List<QuotaEntry> ledger(Authentication auth,@RequestParam(defaultValue="50") int limit) { return quota.entries(current.require(auth),limit); }
}
