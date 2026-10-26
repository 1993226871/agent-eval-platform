package io.agenteval.model;
import java.time.Instant;
import java.util.UUID;
public record ModelDispatch(JudgeRequest request,ModelProfile profile,UUID token,Instant deadline,long upperCredits,UUID evaluationJobId) {
    public ModelDispatch(JudgeRequest request,ModelProfile profile,UUID token,Instant deadline,long upperCredits) { this(request,profile,token,deadline,upperCredits,null); }
}
