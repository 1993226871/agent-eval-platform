package io.agenteval.evaluation;
import io.agenteval.catalog.CatalogService;
import io.agenteval.telemetry.EvidenceService;
import org.springframework.context.annotation.*;
import java.time.Clock;
@Configuration
public class ReevaluationConfiguration {
    @Bean ReevaluationService reevaluationService(ReevaluationRepository repository,CatalogService catalog,EvidenceService evidence,Clock clock) { return new ReevaluationService(repository,catalog,evidence,clock); }
    @Bean EvaluatorPreviewService evaluatorPreviewService(EvaluatorPreviewRepository repository,CatalogService catalog,ReevaluationService evaluations,Clock clock) { return new EvaluatorPreviewService(repository,catalog,evaluations,clock); }
    @Bean EvaluationRunService evaluationRunService(EvaluationRunRepository repository) { return new EvaluationRunService(repository); }
}
