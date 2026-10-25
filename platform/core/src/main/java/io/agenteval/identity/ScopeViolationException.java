package io.agenteval.identity;
public final class ScopeViolationException extends RuntimeException {
    public ScopeViolationException() { super("Resource not found"); }
}
