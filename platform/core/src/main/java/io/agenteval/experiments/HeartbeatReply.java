package io.agenteval.experiments;
import java.time.Instant;
public record HeartbeatReply(boolean cancelRequested,Instant leaseExpiresAt) {}
