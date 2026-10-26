package io.agenteval.quota;
import org.springframework.context.annotation.*;
@Configuration
public class QuotaConfiguration {
    @Bean QuotaService quotaService(QuotaRepository repository) { return new QuotaService(repository); }
}
