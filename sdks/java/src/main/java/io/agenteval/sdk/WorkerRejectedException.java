package io.agenteval.sdk;
public final class WorkerRejectedException extends RuntimeException {
    private final int status;
    public WorkerRejectedException(int status) { super("Platform request rejected (status "+status+")"); this.status=status; }
    public int status() { return status; }
}
