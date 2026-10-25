package io.agenteval.telemetry;
public final class TelemetryConflictException extends RuntimeException {
    public TelemetryConflictException() { super("Conflicting trace identity or content"); }
}
