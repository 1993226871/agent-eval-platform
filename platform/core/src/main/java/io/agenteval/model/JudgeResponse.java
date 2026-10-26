package io.agenteval.model;
import java.util.UUID;
public record JudgeResponse(UUID callId,String status,String content,Long inputTokens,Long outputTokens,Long credits,
    UUID profileId,UUID priceVersionId,String providerModel,String finishReason,String providerResponseId,String failureCode,String rawResponse,Long reportedCredits) {
    @Override public String toString() { return "JudgeResponse[callId="+callId+", status="+status+", response redacted]"; }
}
