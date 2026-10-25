package io.agenteval.experiments;
import io.agenteval.agents.*;
import io.agenteval.catalog.CatalogService;
import org.springframework.context.annotation.*;
import java.time.Clock;
@Configuration
public class ExperimentConfiguration {
    @Bean AgentService agentService(AgentRepository repository) { return new AgentService(repository); }
    @Bean ExperimentService experimentService(ExperimentRepository repository,CatalogService catalog,AgentService agents,Clock clock) { return new ExperimentService(repository,catalog,agents,clock); }
}
