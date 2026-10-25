package io.agenteval.workers;
public final class CapacityExceededException extends RuntimeException {
    public CapacityExceededException() { super("Platform capacity reached"); }
}
