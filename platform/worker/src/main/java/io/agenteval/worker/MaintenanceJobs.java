package io.agenteval.worker;
import io.agenteval.experiments.QueuedTrialExpiry;
import io.agenteval.experiments.LeaseSweeper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public final class MaintenanceJobs {
    private final QueuedTrialExpiry expiry;
    private final LeaseSweeper leases;
    private final io.agenteval.telemetry.EvidenceService evidence;
    public MaintenanceJobs(QueuedTrialExpiry expiry,LeaseSweeper leases,io.agenteval.telemetry.EvidenceService evidence) { this.expiry=expiry; this.leases=leases; this.evidence=evidence; }
    @Scheduled(fixedDelay=5000) public void expireQueuedTrials() { expiry.expire(200); }
    @Scheduled(fixedDelay=5000) public void expireLostLeases() { leases.sweep(200); }
    @Scheduled(fixedDelay=5000) public void freezeEvidence() { evidence.freezeReady(200); }
}
