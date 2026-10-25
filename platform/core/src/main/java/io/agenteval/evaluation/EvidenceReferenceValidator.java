package io.agenteval.evaluation;
import io.agenteval.protocol.EvaluationContext;
import java.util.*;
public final class EvidenceReferenceValidator {
    public void validate(EvaluationContext context,List<String> references) {
        if(references==null || references.size()>1000) throw new IllegalArgumentException("Invalid evidence references");
        var allowed=new HashSet<String>(); allowed.add("snapshot:"+context.snapshotId());
        if(!context.output().isMissingNode()) allowed.add("output");
        if(!context.expected().isMissingNode()) allowed.add("expected");
        if(!context.taskInput().isMissingNode()) allowed.add("task_input");
        context.events().forEach(event -> allowed.add("event:"+event.eventId())); context.artifacts().forEach(artifact -> allowed.add("artifact:"+artifact.artifactId()));
        if(references.stream().anyMatch(reference -> !allowed.contains(reference))) throw new IllegalArgumentException("Unknown evidence reference");
    }
}
