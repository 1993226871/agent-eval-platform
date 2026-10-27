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
    public record WalletView(UUID workspaceId,String granted,String consumed,String reserved,String available,String unit) {
        public static WalletView of(QuotaWallet wallet) { return new WalletView(wallet.workspaceId(),Long.toString(wallet.granted()),Long.toString(wallet.consumed()),Long.toString(wallet.reserved()),Long.toString(wallet.available()),wallet.unit()); }
    }
    public record LedgerEntry(UUID id,UUID callId,String type,String grantedDelta,String consumedDelta,String reservedDelta,String reason,UUID actorUserId,java.time.Instant createdAt) {
        static LedgerEntry of(QuotaEntry entry) { return new LedgerEntry(entry.id(),entry.callId(),entry.type(),Long.toString(entry.grantedDelta()),Long.toString(entry.consumedDelta()),Long.toString(entry.reservedDelta()),entry.reason(),entry.actorUserId(),entry.createdAt()); }
    }
    @GetMapping public WalletView wallet(Authentication auth) { return WalletView.of(quota.wallet(current.require(auth))); }
    @GetMapping("/ledger") public List<LedgerEntry> ledger(Authentication auth,@RequestParam(defaultValue="50") int limit) { return quota.entries(current.require(auth),limit).stream().map(LedgerEntry::of).toList(); }
    public record LedgerPage(List<LedgerEntry> items,String nextCursor) {}
    @GetMapping("/ledger/page") public LedgerPage page(Authentication auth,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="25") int limit) {
        var page=quota.page(current.require(auth),cursor,limit); return new LedgerPage(page.items().stream().map(LedgerEntry::of).toList(),page.nextCursor());
    }
}
