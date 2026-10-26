package io.agenteval.administration;
public final class ResourceReadLimitException extends RuntimeException {
    public ResourceReadLimitException() { super("Body page exceeds limit; use metadata listings"); }
}
