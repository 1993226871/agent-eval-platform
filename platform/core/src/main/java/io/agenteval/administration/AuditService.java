package io.agenteval.administration;

import io.agenteval.identity.*;
import java.util.*;

public final class AuditService {
    public interface Repository {
        Page list(UUID workspace,String cursor,int limit);
        void append(WorkspaceActor actor,AuditAction action,UUID entity);
    }
    public record Page(List<AuditEvent> items,String nextCursor) { public Page { items=List.copyOf(items); } }
    private final Repository repository;
    public AuditService(Repository repository) { this.repository=repository; }
    public Page list(WorkspaceActor actor,String cursor,int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("Invalid audit page"); return repository.list(actor.workspaceId(),cursor,limit);
    }
    public void record(WorkspaceActor actor,AuditAction action,UUID entity) { repository.append(Objects.requireNonNull(actor),Objects.requireNonNull(action),Objects.requireNonNull(entity)); }
    public void accountProvisioned(WorkspaceActor admin,AccountSummary account) {
        if(!admin.admin()) throw new ScopeViolationException(); repository.append(new WorkspaceActor(admin.userId(),account.workspaceId(),true),AuditAction.ACCOUNT_PROVISIONED,account.userId());
    }
}
