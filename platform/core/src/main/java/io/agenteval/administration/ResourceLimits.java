package io.agenteval.administration;

public record ResourceLimits(int maxOnlineWorkers,int maxRunningTrials,long maxStoredBytes,int maxExperiments,
    int maxCatalogResources,int maxPublishedVersions,long maxTrialExecutions,long maxEvaluationJobs,long maxPythonSeconds) {
    public ResourceLimits {
        if(maxOnlineWorkers<1 || maxOnlineWorkers>50 || maxRunningTrials<1 || maxRunningTrials>20 || maxStoredBytes<1 || maxStoredBytes>107374182400L
            || maxExperiments<1 || maxExperiments>10000 || maxCatalogResources<1 || maxCatalogResources>10000 || maxPublishedVersions<1 || maxPublishedVersions>100000
            || maxTrialExecutions<1 || maxTrialExecutions>1000000 || maxEvaluationJobs<1 || maxEvaluationJobs>1000000 || maxPythonSeconds<30 || maxPythonSeconds>2592000)
            throw new IllegalArgumentException("Invalid resource limits");
    }
    public static ResourceLimits defaults() { return new ResourceLimits(5,2,1073741824L,200,300,1000,10000,50000,3600); }
}
