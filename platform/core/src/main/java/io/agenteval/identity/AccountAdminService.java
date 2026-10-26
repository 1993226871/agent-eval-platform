package io.agenteval.identity;
import java.util.List;
public final class AccountAdminService {
    private final IdentityService identities;
    private final IdentityRepository repository;
    public AccountAdminService(IdentityService identities,IdentityRepository repository) { this.identities=identities; this.repository=repository; }
    public AccountSummary create(WorkspaceActor admin,String email,String temporaryPassword) { require(admin); return AccountSummary.of(identities.createUser(email,temporaryPassword,false)); }
    public List<AccountSummary> list(WorkspaceActor admin,int offset,int limit) { require(admin); if(offset<0 || limit<1 || limit>100) throw new IllegalArgumentException("Invalid account page"); return repository.list(offset,limit); }
    private void require(WorkspaceActor actor) { if(!actor.admin()) throw new ScopeViolationException(); }
}
