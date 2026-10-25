package io.agenteval.worker;
import io.agenteval.experiments.QueuedTrialExpiry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public final class MaintenanceJobs {
    private final QueuedTrialExpiry expiry;
    public MaintenanceJobs(QueuedTrialExpiry expiry) { this.expiry=expiry; }
    @Scheduled(fixedDelay=5000) public void expireQueuedTrials() { expiry.expire(200); }
}
