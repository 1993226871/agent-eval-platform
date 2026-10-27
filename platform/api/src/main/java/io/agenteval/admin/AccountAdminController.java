package io.agenteval.admin;
import io.agenteval.identity.*;
import io.agenteval.security.CurrentActor;
import org.springframework.context.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/admin/users")
public class AccountAdminController {
    private final AccountAdminService service;
    private final CurrentActor current;
    private final io.agenteval.administration.AuditService audit;
    private final io.agenteval.quota.CnyQuotaService money;
    public AccountAdminController(AccountAdminService service,CurrentActor current,io.agenteval.administration.AuditService audit,io.agenteval.quota.CnyQuotaService money) { this.service=service; this.current=current; this.audit=audit; this.money=money; }
    public record Create(String email,String temporaryPassword) { @Override public String toString() { return "CreateAccount[password redacted]"; } }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    public AccountSummary create(Authentication auth,@RequestBody Create command) { var actor=current.require(auth); var account=service.create(actor,command.email(),command.temporaryPassword()); money.openNewAccount(actor,account.workspaceId()); audit.accountProvisioned(actor,account); return account; }
    @GetMapping public List<AccountSummary> list(Authentication auth,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="25") int limit) { return service.list(current.require(auth),offset,limit); }
    @Configuration static class Wiring { @Bean AccountAdminService accountAdminService(IdentityService identities,IdentityRepository repository) { return new AccountAdminService(identities,repository); } }
}
