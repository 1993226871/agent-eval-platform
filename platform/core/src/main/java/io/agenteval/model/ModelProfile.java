package io.agenteval.model;
import java.util.UUID;
/** Safe metadata. Credentials are intentionally absent. */
public record ModelProfile(UUID id,String name,String endpoint,String model,long inputTokenBound,int maxOutputTokens,int maxRequestBytes,int maxConcurrent,String boundReference,boolean enabled,String disabledReason,PriceVersion price) {}
