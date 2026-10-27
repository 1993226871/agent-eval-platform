package io.agenteval.model;
/** Minimal browser input. Billing mode and defaults are never chosen by the caller. */
public record ModelConnection(String model,String endpoint,String apiKey) {
    @Override public String toString() { return "ModelConnection[credentials redacted]"; }
}
