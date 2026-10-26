package io.agenteval.worker;
import io.agenteval.model.ModelGateway;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public final class ModelCallRecovery {
    private final ModelGateway gateway;
    public ModelCallRecovery(ModelGateway gateway) { this.gateway=gateway; }
    @Scheduled(fixedDelayString="${app.models.recovery-interval-ms:5000}") public void recover() { gateway.recover(); }
}
