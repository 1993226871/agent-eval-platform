package io.agenteval.model;
public record ModelProfileCommand(String name,String endpoint,String apiKey,String model,long inputTokenBound,int maxOutputTokens,int maxRequestBytes,int maxConcurrent,String boundReference,long inputRate,long outputRate,long denominator,String billingUnit) {
    public ModelProfileCommand(String name,String endpoint,String apiKey,String model,long inputTokenBound,int maxOutputTokens,int maxRequestBytes,int maxConcurrent,String boundReference,long inputRate,long outputRate,long denominator) {
        this(name,endpoint,apiKey,model,inputTokenBound,maxOutputTokens,maxRequestBytes,maxConcurrent,boundReference,inputRate,outputRate,denominator,"CREDITS");
    }
    @Override public String toString() { return "ModelProfileCommand[credentials redacted]"; }
}
