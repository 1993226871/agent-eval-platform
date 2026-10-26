package io.agenteval.evaluation;
import io.agenteval.infrastructure.evaluation.RunnerSandboxClient;
import io.agenteval.model.ModelGateway;
import io.agenteval.protocol.*;
import io.agenteval.telemetry.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.net.URI;
import java.time.Clock;
import java.util.*;

@Configuration
public class EvaluationEngineConfiguration {
    @Bean(destroyMethod="close") @ConditionalOnProperty(name="app.runner.enabled",havingValue="true")
    RunnerSandboxClient runnerSandboxClient(ArtifactService artifacts,@Value("${app.runner.base-url}") URI base,@Value("${app.runner.internal-key}") String key,@Value("${app.runner.image-digest}") String image) {
        return new RunnerSandboxClient(base,key,image,(job,context)->context.artifacts().stream().map(artifact->new RunnerSandboxClient.ArtifactDownload(artifact.artifactId(),artifacts.downloadEvidence(job.owner(),job.trialId(),job.snapshotId(),artifact).toString())).toList());
    }
    @Bean @ConditionalOnProperty(name="app.runner.enabled",havingValue="false",matchIfMissing=true)
    SandboxClient disabledSandboxClient() {
        return (job,context)->new CriterionResult(job.definition().criterionId(),CriterionStatus.SKIPPED,null,null,"CAPABILITY_UNAVAILABLE",List.of(),job.definition().evaluatorVersion());
    }
    @Bean EvaluationOrchestrator evaluationOrchestrator(EvaluationRepository repository,EvidenceService evidence,SandboxClient sandbox,ModelGateway models,Clock clock) {
        return new EvaluationOrchestrator(repository,evidence,Map.of("RULE",new RuleEvaluator(),"PYTHON",new PythonEvaluator(sandbox),"LLM",new LlmEvaluator(models)),clock);
    }
}
