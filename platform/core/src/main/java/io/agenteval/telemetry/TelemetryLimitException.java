package io.agenteval.telemetry;
public final class TelemetryLimitException extends RuntimeException {
    public TelemetryLimitException() { super("Trace request exceeds configured limits"); }
}
