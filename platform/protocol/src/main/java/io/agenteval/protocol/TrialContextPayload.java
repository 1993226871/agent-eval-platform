package io.agenteval.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Only public execution input: never put expected answers or evaluator secrets here. */
public record TrialContextPayload(UUID trialId, String instruction, JsonNode input, Instant deadline) {
    public TrialContextPayload {
        Checks.required(trialId,"trial_id"); Checks.text(instruction,"instruction");
        Checks.required(input,"input"); Checks.required(deadline,"deadline"); input=input.deepCopy();
    }
    @Override public JsonNode input() { return input.deepCopy(); }
}
