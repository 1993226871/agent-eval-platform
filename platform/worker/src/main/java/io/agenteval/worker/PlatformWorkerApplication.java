package io.agenteval.worker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;
@EnableScheduling
@SpringBootApplication(scanBasePackages="io.agenteval")
public class PlatformWorkerApplication {
    public static void main(String[] args) { var application=new SpringApplication(PlatformWorkerApplication.class); application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE); application.run(args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean io.agenteval.telemetry.EvidenceService evidenceService(io.agenteval.telemetry.EvidenceRepository repository,Clock clock) { return new io.agenteval.telemetry.EvidenceService(repository,clock); }
    @Bean com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .propertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    }
}
