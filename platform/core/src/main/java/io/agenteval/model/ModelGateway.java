package io.agenteval.model;
import io.agenteval.identity.*;
import java.time.Clock;
import java.util.UUID;
public final class ModelGateway {
    private final ModelCallRepository calls;
    private final JudgeProvider provider;
    private final Clock clock;
    public ModelGateway(ModelCallRepository calls,JudgeProvider provider,Clock clock) { this.calls=calls; this.provider=provider; this.clock=clock; }
    public JudgeResponse judge(JudgeRequest request) {
        var prepared=calls.prepare(request,clock.instant());
        if(prepared.dispatch()==null) return prepared.response();
        var dispatch=prepared.dispatch();
        JudgeProvider.PreparedCall outbound;
        try { outbound=provider.prepare(dispatch); }
        catch(RuntimeException unavailable) { return calls.failUnsent(dispatch,clock.instant()); }
        calls.markSendIntent(dispatch,clock.instant());
        ProviderResponse response;
        try { response=outbound.send(); }
        catch(RuntimeException uncertain) { return calls.uncertain(dispatch,clock.instant()); }
        // DB failure after provider response must propagate; recovery retains the uncertain hold.
        return calls.complete(dispatch,response,clock.instant());
    }
    public void recover() { calls.recover(clock.instant()); }
    public java.util.List<ModelCallSummary> pending(WorkspaceActor admin,int limit) {
        if(!admin.admin()) throw new ScopeViolationException();
        if(limit<1 || limit>100) throw new IllegalArgumentException("Invalid reconciliation limit");
        return calls.pending(limit);
    }
    public JudgeResponse reconcile(WorkspaceActor admin,UUID callId,long inputTokens,long outputTokens,String reason,UUID key) {
        if(!admin.admin()) throw new ScopeViolationException();
        if(callId==null || inputTokens<0 || outputTokens<0 || key==null || reason==null || reason.isBlank() || reason.length()>500) throw new IllegalArgumentException("Invalid reconciliation");
        return calls.reconcile(admin,callId,inputTokens,outputTokens,reason,key,clock.instant());
    }
}
