package io.agenteval.workers;
import io.agenteval.agents.AgentService;
import io.agenteval.experiments.*;
import org.springframework.context.annotation.*;
import java.time.Clock;
@Configuration
public class WorkerConfiguration {
    @Bean WorkerCredentialService workerCredentialService(CredentialRepository repository,AgentService agents) { return new WorkerCredentialService(repository,agents); }
    @Bean WorkerRegistry workerRegistry(WorkerRegistry.Repository repository,Clock clock) { return new WorkerRegistry(repository,clock); }
    @Bean LeaseService leaseService(LeaseRepository repository,Clock clock) { return new LeaseService(repository,clock); }
}
