package io.agenteval.model;
import java.time.Instant;
import java.util.UUID;
/** Decimal strings preserve all 64-bit monetary/token values in JavaScript clients. */
public final class ModelViews {
    private ModelViews() {}
    public static String exact(Long value) { return value==null?null:Long.toString(value); }
    public record Price(UUID id,String inputRate,String outputRate,String denominator) {
        public static Price of(PriceVersion price) { return new Price(price.id(),Long.toString(price.inputRate()),Long.toString(price.outputRate()),Long.toString(price.denominator())); }
    }
    public record Profile(UUID id,String name,String endpoint,String model,long inputTokenBound,int maxOutputTokens,int maxRequestBytes,int maxConcurrent,String boundReference,boolean enabled,String disabledReason,Price price) {
        public static Profile of(ModelProfile profile) { return new Profile(profile.id(),profile.name(),profile.endpoint(),profile.model(),profile.inputTokenBound(),profile.maxOutputTokens(),profile.maxRequestBytes(),profile.maxConcurrent(),profile.boundReference(),profile.enabled(),profile.disabledReason(),Price.of(profile.price())); }
    }
    public record Pending(UUID callId,UUID workspaceId,UUID profileId,UUID priceVersionId,String status,String reservedCredits,String inputTokens,String outputTokens,String reportedCredits,String failureCode,Instant createdAt) {
        public static Pending of(ModelCallSummary call) { return new Pending(call.callId(),call.workspaceId(),call.profileId(),call.priceVersionId(),call.status(),Long.toString(call.reservedCredits()),exact(call.inputTokens()),exact(call.outputTokens()),exact(call.reportedCredits()),call.failureCode(),call.createdAt()); }
    }
}
