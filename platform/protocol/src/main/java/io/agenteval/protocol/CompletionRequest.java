package io.agenteval.protocol;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public record CompletionRequest(TrialExecutionStatus status,JsonNode output,long finalSequence,long eventCount,long droppedCount,List<UUID> artifactIds) {
    public CompletionRequest {
        if(status!=TrialExecutionStatus.SUCCEEDED && status!=TrialExecutionStatus.FAILED && status!=TrialExecutionStatus.TIMED_OUT && status!=TrialExecutionStatus.CANCELLED)
            throw new IllegalArgumentException("Worker may report only its terminal execution status");
        if(finalSequence<0 || eventCount<0 || droppedCount<0 || eventCount>finalSequence || droppedCount>finalSequence-eventCount)
            throw new IllegalArgumentException("Invalid completion counts");
        Checks.required(output,"output"); output=output.deepCopy(); artifactIds=List.copyOf(artifactIds);
    }
    @Override public JsonNode output() { return output.deepCopy(); }
}
