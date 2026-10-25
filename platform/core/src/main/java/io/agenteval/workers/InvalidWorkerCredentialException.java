package io.agenteval.workers;
public final class InvalidWorkerCredentialException extends RuntimeException {
    public InvalidWorkerCredentialException() { super("Invalid worker credential"); }
}
