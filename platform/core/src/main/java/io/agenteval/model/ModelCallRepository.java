package io.agenteval.model;
import io.agenteval.identity.WorkspaceActor;
import java.time.Instant;
import java.util.UUID;
public interface ModelCallRepository {
    record Preparation(JudgeResponse response,ModelDispatch dispatch) {}
    default Preparation prepare(JudgeRequest request,Instant now) { return prepare(request,now,null); }
    Preparation prepare(JudgeRequest request,Instant now,UUID evaluationJobId);
    JudgeResponse markSendIntent(ModelDispatch dispatch,Instant now);
    JudgeResponse complete(ModelDispatch dispatch,ProviderResponse response,Instant now);
    JudgeResponse uncertain(ModelDispatch dispatch,Instant now);
    JudgeResponse failUnsent(ModelDispatch dispatch,Instant now);
    void recover(Instant now);
    java.util.List<ModelCallSummary> pending(int limit);
    JudgeResponse reconcile(WorkspaceActor admin,UUID callId,long inputTokens,long outputTokens,String reason,UUID key,Instant now);
}
