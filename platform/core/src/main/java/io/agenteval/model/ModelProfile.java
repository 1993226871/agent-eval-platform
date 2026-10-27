package io.agenteval.model;
import java.util.UUID;
/** Safe metadata. Credentials are intentionally absent. */
public record ModelProfile(UUID id,String name,String endpoint,String model,long inputTokenBound,int maxOutputTokens,int maxRequestBytes,int maxConcurrent,String boundReference,boolean enabled,String disabledReason,PriceVersion price,UUID workspaceId,String billingMode) {
    public ModelProfile(UUID id,String name,String endpoint,String model,long inputTokenBound,int maxOutputTokens,int maxRequestBytes,int maxConcurrent,String boundReference,boolean enabled,String disabledReason,PriceVersion price) {
        this(id,name,endpoint,model,inputTokenBound,maxOutputTokens,maxRequestBytes,maxConcurrent,boundReference,enabled,disabledReason,price,null,"PLATFORM");
    }
    public boolean usesPlatformQuota() { return billingMode.equals("PLATFORM"); }
    public void requireWorkspace(UUID workspace) {
        if(workspaceId!=null && !workspaceId.equals(workspace)) throw new io.agenteval.identity.ScopeViolationException();
    }
    public boolean visibleTo(io.agenteval.identity.WorkspaceActor actor) { return workspaceId==null || workspaceId.equals(actor.workspaceId()); }
}
