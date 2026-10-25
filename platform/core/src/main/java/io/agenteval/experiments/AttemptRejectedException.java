package io.agenteval.experiments;
public final class AttemptRejectedException extends RuntimeException {
    public AttemptRejectedException() { super("Attempt is expired, stale or incompatible with current state"); }
}
