package io.agenteval.telemetry;
import com.fasterxml.jackson.databind.JsonNode;
import io.agenteval.protocol.EvidenceCompleteness;
import java.time.Instant;
import java.util.UUID;
public record EvidenceSnapshot(UUID id,UUID trialId,String contentHash,EvidenceCompleteness completeness,JsonNode manifest,Instant createdAt) {
    public EvidenceSnapshot { manifest=manifest.deepCopy(); }
    @Override public JsonNode manifest() { return manifest.deepCopy(); }
}
