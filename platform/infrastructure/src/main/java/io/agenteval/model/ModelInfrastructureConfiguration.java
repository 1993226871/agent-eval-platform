package io.agenteval.model;
import io.agenteval.quota.QuotaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

@Configuration
public class ModelInfrastructureConfiguration {
    @Bean ModelSecretBox modelSecretBox(@Value("${MODEL_ENCRYPTION_KEY:${app.models.encryption-key:}}") String key) {
        return new ModelSecretBox(key.isBlank()?null:Base64.getDecoder().decode(key));
    }
    @Bean EncryptedModelProfileRepository modelProfileRepository(JdbcClient jdbc,PlatformTransactionManager manager,ModelSecretBox secrets,@Value("${MODEL_ENDPOINT_ORIGINS:${app.models.endpoint-origins:}}") String origins) {
        return new EncryptedModelProfileRepository(jdbc,manager,secrets,Arrays.stream(origins.split(",")).map(String::strip).filter(value -> !value.isBlank()).collect(Collectors.toSet()));
    }
    @Bean ModelProfileService modelProfileService(EncryptedModelProfileRepository profiles) { return new ModelProfileService(profiles); }
    @Bean JdbcModelCallRepository modelCallRepository(JdbcClient jdbc,PlatformTransactionManager manager,EncryptedModelProfileRepository profiles,QuotaService quota) { return new JdbcModelCallRepository(jdbc,manager,profiles,quota); }
    @Bean OpenAiCompatibleJudgeClient judgeProvider(EncryptedModelProfileRepository profiles) { return new OpenAiCompatibleJudgeClient(profiles); }
    @Bean ModelGateway modelGateway(JdbcModelCallRepository calls,OpenAiCompatibleJudgeClient provider,Clock clock) { return new ModelGateway(calls,provider,clock); }
}
