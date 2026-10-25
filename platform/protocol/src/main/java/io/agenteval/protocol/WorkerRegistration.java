package io.agenteval.protocol;
import java.util.UUID;
public record WorkerRegistration(UUID workerId,int heartbeatSeconds,int leaseSeconds) {}
