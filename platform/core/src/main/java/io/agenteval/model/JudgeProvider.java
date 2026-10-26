package io.agenteval.model;
public interface JudgeProvider {
    PreparedCall prepare(ModelDispatch dispatch);
    @FunctionalInterface interface PreparedCall { ProviderResponse send(); }
}
