package io.agenteval.telemetry;
public final class TraceInputLimitException extends java.io.IOException {
    public TraceInputLimitException() { super("Trace request exceeds byte limit"); }
}
