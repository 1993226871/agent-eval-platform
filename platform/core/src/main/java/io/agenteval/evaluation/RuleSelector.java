package io.agenteval.evaluation;
import com.fasterxml.jackson.databind.JsonNode;
import io.agenteval.protocol.*;
import java.util.*;
public final class RuleSelector {
    public Selection select(EvaluationContext context,String pointer) {
        validateEvidencePointer(pointer);
        int slash=pointer.indexOf('/',1); String root=slash<0?pointer.substring(1):pointer.substring(1,slash); String remainder=slash<0?"":pointer.substring(slash);
        JsonNode source=switch(root) { case "output" -> context.output(); case "expected" -> context.expected(); case "task_input" -> context.taskInput(); default -> throw new IllegalArgumentException("Unsupported evidence selector"); };
        return new Selection(at(source,remainder),root);
    }
    public JsonNode at(JsonNode source,String pointer) { if(!pointer.isEmpty()) validatePointer(pointer); return pointer.isEmpty()?source:source.at(pointer); }
    public static void validatePointer(String pointer) {
        if(pointer==null || pointer.length()>512 || !pointer.startsWith("/") || pointer.chars().filter(c -> c=='/').count()>32 || pointer.matches(".*~(?:[^01]|$).*") || pointer.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid JSON Pointer");
    }
    public static void validateEvidencePointer(String pointer) { validatePointer(pointer); int slash=pointer.indexOf('/',1); String root=slash<0?pointer.substring(1):pointer.substring(1,slash); if(!Set.of("output","expected","task_input").contains(root)) throw new IllegalArgumentException("Unsupported evidence selector"); }
    public Map<String,TraceEvent> starts(EvaluationContext context) {
        var result=new LinkedHashMap<String,TraceEvent>();
        for(var event:context.events()) if(event.eventType()==EventType.SPAN_STARTED) result.putIfAbsent(event.spanId(),event);
        return Collections.unmodifiableMap(result);
    }
    public List<ToolCall> tools(EvaluationContext context,String tool) {
        var terminal=new HashMap<String,TraceEvent>(); for(var event:context.events()) if(event.eventType()==EventType.SPAN_COMPLETED || event.eventType()==EventType.SPAN_FAILED) terminal.putIfAbsent(event.spanId(),event);
        return starts(context).values().stream().filter(event -> event.spanKind()==SpanKind.TOOL && (tool==null || event.name().equals(tool))).map(event -> new ToolCall(event,terminal.get(event.spanId()))).toList();
    }
    public record Selection(JsonNode value,String reference) {}
    public record ToolCall(TraceEvent start,TraceEvent end) {}
}
