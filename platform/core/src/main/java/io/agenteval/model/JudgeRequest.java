package io.agenteval.model;
import java.util.UUID;
public record JudgeRequest(UUID workspaceId,UUID callId,UUID modelProfileId,UUID priceVersionId,String systemPrompt,String userPrompt,int maxOutputTokens) {
    public JudgeRequest {
        if(workspaceId==null || callId==null || modelProfileId==null || priceVersionId==null || systemPrompt==null || userPrompt==null || systemPrompt.isBlank() || maxOutputTokens<1)
            throw new IllegalArgumentException("Invalid Judge request");
        if(systemPrompt.length()>256*1024 || userPrompt.length()>256*1024) throw new IllegalArgumentException("Judge input limit exceeded");
    }
    @Override public String toString() { return "JudgeRequest[callId="+callId+", prompts redacted]"; }
}
