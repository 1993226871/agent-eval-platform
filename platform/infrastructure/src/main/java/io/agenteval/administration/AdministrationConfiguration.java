package io.agenteval.administration;

import io.agenteval.experiments.*;
import io.agenteval.telemetry.ArtifactStore;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class AdministrationConfiguration {
    @Bean AuditService auditService(AuditService.Repository repository) { return new AuditService(repository); }
    @Bean ResourceLimitService resourceLimitService(ResourceLimitService.Repository repository) { return new ResourceLimitService(repository); }
    @Bean ExperimentDeletionService experimentDeletionService(ExperimentDeletionRepository repository,ArtifactStore store,RunnerEvidenceCleaner runner,Clock clock) { return new ExperimentDeletionService(repository,store,runner,clock); }
}
