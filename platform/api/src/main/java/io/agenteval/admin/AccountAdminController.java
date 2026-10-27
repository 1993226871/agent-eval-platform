package io.agenteval.admin;
import io.agenteval.identity.*;
import io.agenteval.security.CurrentActor;
import org.springframework.context.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/admin/users")
public class AccountAdminController {
    private final AccountAdminService service;
    private final CurrentActor current;
    private final io.agenteval.administration.AuditService audit;
    private final io.agenteval.quota.CnyQuotaService money;
    private final io.agenteval.quota.QuotaService quota;
    public AccountAdminController(AccountAdminService service,CurrentActor current,io.agenteval.administration.AuditService audit,io.agenteval.quota.CnyQuotaService money,io.agenteval.quota.QuotaService quota) { this.service=service; this.current=current; this.audit=audit; this.money=money; this.quota=quota; }
    public record Create(String email,String temporaryPassword) { @Override public String toString() { return "CreateAccount[password redacted]"; } }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    public AccountSummary create(Authentication auth,@RequestBody Create command) { var actor=current.require(auth); var account=service.create(actor,command.email(),command.temporaryPassword()); money.openNewAccount(actor,account.workspaceId()); audit.accountProvisioned(actor,account); return account; }
    public record AccountView(UUID userId,UUID workspaceId,String email,boolean admin,boolean mustChangePassword,io.agenteval.quota.QuotaController.WalletView quota) {}
    @GetMapping public List<AccountView> list(Authentication auth,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="25") int limit) {
        var actor=current.require(auth); var accounts=service.list(actor,offset,limit);
        var wallets=quota.walletsForAdministrator(actor,accounts.stream().map(AccountSummary::workspaceId).toList());
        return accounts.stream().map(account->new AccountView(account.userId(),account.workspaceId(),account.email(),account.admin(),account.mustChangePassword(),io.agenteval.quota.QuotaController.WalletView.of(wallets.get(account.workspaceId())))).toList();
    }
    @Configuration static class Wiring { @Bean AccountAdminService accountAdminService(IdentityService identities,IdentityRepository repository) { return new AccountAdminService(identities,repository); } }
}
