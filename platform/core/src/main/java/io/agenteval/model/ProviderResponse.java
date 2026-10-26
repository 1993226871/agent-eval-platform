package io.agenteval.model;
public record ProviderResponse(String content,Long inputTokens,Long outputTokens,String model,String finishReason,String responseId,String rawResponse) {
    @Override public String toString() { return "ProviderResponse[redacted]"; }
}
