package io.agenteval.telemetry;
import org.springframework.context.annotation.*;
import java.time.Clock;
@Configuration
public class TelemetryConfiguration {
    @Bean TelemetryService telemetryService(TelemetryRepository repository,Clock clock) { return new TelemetryService(repository,clock); }
    @Bean EvidenceService evidenceService(EvidenceRepository repository,Clock clock) { return new EvidenceService(repository,clock); }
    @Bean ArtifactService artifactService(ArtifactRepository repository,ArtifactStore store,Clock clock) { return new ArtifactService(repository,store,clock); }
}
