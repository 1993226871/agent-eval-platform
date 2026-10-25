package io.agenteval.telemetry;
import org.springframework.context.annotation.*;
import java.time.Clock;
@Configuration
public class TelemetryConfiguration {
    @Bean TelemetryService telemetryService(TelemetryRepository repository,Clock clock) { return new TelemetryService(repository,clock); }
}
