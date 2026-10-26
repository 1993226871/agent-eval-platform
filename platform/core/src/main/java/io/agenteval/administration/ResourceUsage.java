package io.agenteval.administration;
public record ResourceUsage(long trialExecutions,long evaluationJobs,long pythonReservedSeconds,long artifactBytes,long evidenceBytes) {}
