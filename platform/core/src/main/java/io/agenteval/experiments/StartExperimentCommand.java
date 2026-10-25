package io.agenteval.experiments;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
public record StartExperimentCommand(UUID agentId,String agentVersion,UUID datasetVersionId,UUID suiteVersionId,
    int repeatCount,Instant deadline,String idempotencyKey) {
    public StartExperimentCommand {
        Objects.requireNonNull(agentId); Objects.requireNonNull(datasetVersionId); Objects.requireNonNull(suiteVersionId); Objects.requireNonNull(deadline);
        if(agentVersion==null || agentVersion.isBlank() || agentVersion.length()>128 || repeatCount<1 || repeatCount>20
            || idempotencyKey==null || !idempotencyKey.matches("[a-zA-Z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid experiment configuration");
    }
}
