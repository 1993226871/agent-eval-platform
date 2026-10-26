package io.agenteval.protocol;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** Server-side evaluator evidence. Never use this object as an Agent task payload. */
public record EvaluationContext(UUID trialId,UUID snapshotId,JsonNode taskInput,JsonNode expected,JsonNode output,
    List<TraceEvent> events,List<ArtifactRef> artifacts,EvidenceCompleteness completeness,String taskInstruction) {
    /** Existing rule callers may omit an instruction; LLM evidence explicitly carries it. */
    public EvaluationContext(UUID trialId,UUID snapshotId,JsonNode taskInput,JsonNode expected,JsonNode output,List<TraceEvent> events,List<ArtifactRef> artifacts,EvidenceCompleteness completeness) {
        this(trialId,snapshotId,taskInput,expected,output,events,artifacts,completeness,"");
    }
    public EvaluationContext {
        Checks.required(trialId,"trial_id"); Checks.required(snapshotId,"snapshot_id");
        Checks.required(taskInstruction,"task_instruction");
        taskInput=Checks.required(taskInput,"task_input").deepCopy();
        expected=Checks.required(expected,"expected").deepCopy(); output=Checks.required(output,"output").deepCopy();
        events=List.copyOf(events); artifacts=List.copyOf(artifacts); Checks.required(completeness,"completeness");
        if(events.stream().anyMatch(event -> !event.trialId().equals(trialId))) throw new IllegalArgumentException("Foreign trial evidence");
    }
    @Override public JsonNode taskInput() { return taskInput.deepCopy(); }
    @Override public JsonNode expected() { return expected.deepCopy(); }
    @Override public JsonNode output() { return output.deepCopy(); }
}
