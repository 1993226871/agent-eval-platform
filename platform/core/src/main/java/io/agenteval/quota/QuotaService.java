package io.agenteval.quota;
import io.agenteval.identity.*;
import java.util.*;

/** Reserve/transition methods are internal capabilities, never bound to browser request IDs. */
public final class QuotaService {
    private final QuotaRepository repository;
    public QuotaService(QuotaRepository repository) { this.repository=repository; }
    public QuotaWallet wallet(WorkspaceActor actor) { return repository.wallet(actor.workspaceId()); }
    public List<QuotaEntry> entries(WorkspaceActor actor,int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("Invalid ledger limit");
        return repository.entries(actor.workspaceId(),limit);
    }
    public QuotaLedgerPage page(WorkspaceActor actor,String cursor,int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("Invalid ledger page"); return repository.page(actor.workspaceId(),cursor,limit);
    }
    public QuotaWallet grant(WorkspaceActor admin,UUID workspaceId,long credits,String reason,UUID idempotencyKey) {
        if(!admin.admin()) throw new ScopeViolationException();
        if(workspaceId==null || idempotencyKey==null || credits==0 || reason==null || reason.isBlank() || reason.length()>500)
            throw new IllegalArgumentException("Invalid quota adjustment");
        return repository.adjust(workspaceId,credits,reason,idempotencyKey,admin.userId());
    }
    public Reservation reserve(UUID workspaceId,UUID callId,long upperCredits) {
        if(workspaceId==null || callId==null || upperCredits<=0) throw new IllegalArgumentException("Invalid reservation");
        return repository.reserve(workspaceId,callId,upperCredits);
    }
    public Reservation settle(UUID callId,long actualCredits) {
        if(callId==null || actualCredits<0) throw new IllegalArgumentException("Invalid settlement");
        return repository.transition(callId,"SETTLE",actualCredits);
    }
    public Reservation releaseUnsent(UUID callId) { return repository.transition(Objects.requireNonNull(callId),"RELEASE",null); }
    public Reservation markSendIntent(UUID callId) { return repository.transition(Objects.requireNonNull(callId),"SEND_INTENT",null); }
    public Reservation markReconciliation(UUID callId) { return repository.transition(Objects.requireNonNull(callId),"RECONCILIATION",null); }
    public Reservation approveOverage(WorkspaceActor admin,UUID callId,long actualCredits,String reason,UUID key) {
        if(!admin.admin()) throw new ScopeViolationException();
        if(callId==null || key==null || actualCredits<0 || reason==null || reason.isBlank() || reason.length()>500) throw new IllegalArgumentException("Invalid overage confirmation");
        return repository.approveOverage(callId,actualCredits,admin.userId(),reason,key);
    }
}
