package io.agenteval.model;
public record ModelProfileCommand(String name,String endpoint,String apiKey,String model,long inputTokenBound,int maxOutputTokens,int maxRequestBytes,int maxConcurrent,String boundReference,long inputRate,long outputRate,long denominator) {
    @Override public String toString() { return "ModelProfileCommand[credentials redacted]"; }
}
