package io.agenteval.evaluation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.agenteval.protocol.*;
import java.math.BigDecimal;
import java.util.*;

public final class RuleEvaluator implements Evaluator {
    private static final Set<String> OPERATORS=Set.of("eq","ne","gt","gte","lt","lte","between","exists","schema","tool_set","tool_arguments","causal_before","count","duration_ms");
    private static final Set<String> KEYS=Set.of("operator","selector","expected","expected_selector","schema","tool","tools","mode","comparison","aggregation","match_count","before","after","relation");
    private static final Set<String> COMPARISONS=Set.of("eq","ne","gt","gte","lt","lte","between");
    private final RuleSelector selector=new RuleSelector();
    private final EvidenceReferenceValidator references=new EvidenceReferenceValidator();
    private final BoundedJsonSchema schemas=new BoundedJsonSchema();
    @Override public CriterionResult evaluate(EvaluatorDefinition definition,EvaluationContext context) {
        try {
            validate(definition); requireEvidence(definition,context); var config=definition.config(); String operator=text(config,"operator");
            var refs=new LinkedHashSet<String>(); boolean matched;
            switch(operator) {
                case "tool_set" -> matched=toolSet(context,config,refs);
                case "tool_arguments" -> matched=arguments(context,config,refs);
                case "causal_before" -> matched=causal(context,config,refs);
                case "count" -> matched=count(context,config,refs);
                case "duration_ms" -> matched=duration(context,config,refs);
                default -> {
                    var selection=selector.select(context,text(config,"selector")); var value=selection.value(); refs.add(selection.reference());
                    if(operator.equals("exists")) {
                        if(!config.path("expected").isBoolean()) throw new IllegalArgumentException("Existence requires a boolean expectation");
                        if(value.isMissingNode() && (config.get("expected").asBoolean() || context.completeness()!=EvidenceCompleteness.COMPLETE)) throw new MissingEvidence();
                        matched=(!value.isMissingNode())==config.get("expected").asBoolean();
                    } else {
                        if(value.isMissingNode()) throw new MissingEvidence();
                        if(operator.equals("schema")) matched=schemas.matches(value,config.get("schema"));
                        else matched=compare(value,expected(context,config,refs),operator);
                    }
                }
            }
            refs.add("snapshot:"+context.snapshotId()); var evidence=refs.stream().limit(128).toList(); references.validate(context,evidence);
            var score=matched?BigDecimal.ONE:BigDecimal.ZERO;
            return new CriterionResult(definition.criterionId(),CriterionStatus.SCORED,score,score.compareTo(definition.threshold())>=0,"Deterministic rule "+(matched?"matched":"did not match"),evidence,definition.evaluatorVersion());
        } catch(MissingEvidence missing) { return unscored(definition,CriterionStatus.INSUFFICIENT_EVIDENCE,"Required evidence cannot establish this assertion"); }
        catch(IllegalArgumentException invalid) { return unscored(definition,CriterionStatus.ERROR,"Invalid rule configuration or unsupported evidence value"); }
    }
    public void validate(EvaluatorDefinition definition) {
        if(!"RULE".equals(definition.type()) || definition.threshold()==null || definition.threshold().compareTo(BigDecimal.ZERO)<0 || definition.threshold().compareTo(BigDecimal.ONE)>0) throw new IllegalArgumentException("Invalid rule definition");
        var config=definition.config(); if(!config.isObject() || config.toString().length()>65536) throw new IllegalArgumentException("Invalid rule config");
        config.fieldNames().forEachRemaining(key -> { if(!KEYS.contains(key)) throw new IllegalArgumentException("Unsupported rule field"); });
        String op=text(config,"operator"); if(!OPERATORS.contains(op)) throw new IllegalArgumentException("Unsupported rule operator");
        if(Set.of("eq","ne","gt","gte","lt","lte","between","exists","schema").contains(op)) RuleSelector.validateEvidencePointer(text(config,"selector"));
        if(config.has("expected_selector")) RuleSelector.validateEvidencePointer(text(config,"expected_selector"));
        switch(op) {
            case "exists" -> { if(!config.path("expected").isBoolean()) throw new IllegalArgumentException("Existence requires a boolean expectation"); }
            case "schema" -> schemas.check(config.path("schema"));
            case "tool_set" -> {
                var tools=config.path("tools"); if(!tools.isArray() || tools.isEmpty() || tools.size()>64) throw new IllegalArgumentException("Invalid tool set");
                for(var tool:tools) if(!tool.isTextual() || tool.asText().isBlank() || tool.asText().length()>1024) throw new IllegalArgumentException("Invalid tool");
                if(!Set.of("absent","contains_all","exact").contains(text(config,"mode"))) throw new IllegalArgumentException("Invalid tool-set mode");
            }
            case "causal_before" -> { text(config,"before"); text(config,"after"); if(!Set.of("ancestor","dependency").contains(text(config,"relation"))) throw new IllegalArgumentException("Invalid causal relation"); validateAggregation(config); }
            default -> {
                if(config.has("expected")==config.has("expected_selector")) throw new IllegalArgumentException("Specify exactly one expectation");
                String comparison=Set.of("tool_arguments","count","duration_ms").contains(op)?text(config,"comparison"):op;
                if(!COMPARISONS.contains(comparison)) throw new IllegalArgumentException("Unsupported comparison");
                if(config.has("expected")) { guardExpected(config.get("expected"),0,new int[]{0}); if(!Set.of("eq","ne").contains(comparison) || op.equals("duration_ms")) validateNumericExpectation(config.get("expected"),comparison); if(op.equals("count")) validateCountExpectation(config.get("expected"),comparison); }
                if(Set.of("tool_arguments","count","duration_ms").contains(op)) text(config,"tool");
                if(op.equals("tool_arguments")) { RuleSelector.validatePointer(text(config,"selector")); validateAggregation(config); }
                if(op.equals("duration_ms") && !Set.of("any","all","sum","min","max").contains(text(config,"aggregation"))) throw new IllegalArgumentException("Invalid duration aggregation");
            }
        }
        for(String need:definition.requiredEvidence()) if(!Set.of("output","expected","task_input","events","trace","artifacts").contains(need)) throw new IllegalArgumentException("Unsupported required evidence");
    }
    private void validateAggregation(JsonNode config) {
        String aggregation=text(config,"aggregation"); if(!Set.of("any","all","count").contains(aggregation)) throw new IllegalArgumentException("Invalid aggregation");
        if(aggregation.equals("count")) { var count=number(config.path("match_count")); if(count.stripTrailingZeros().scale()>0 || count.signum()<0 || count.compareTo(BigDecimal.valueOf(10000))>0) throw new IllegalArgumentException("Invalid match count"); }
    }
    private void guardExpected(JsonNode value,int depth,int[] count) { if(depth>16 || ++count[0]>1024) throw new IllegalArgumentException("Expectation complexity limit"); if(value.isNumber()) number(value); if(value.isContainerNode()) value.forEach(child -> guardExpected(child,depth+1,count)); }
    private void validateNumericExpectation(JsonNode value,String operation) { if(operation.equals("between")) { if(!value.isObject() || value.size()!=2 || number(value.path("min")).compareTo(number(value.path("max")))>0) throw new IllegalArgumentException("Invalid range"); } else number(value); }
    private void validateCountExpectation(JsonNode value,String operation) {
        validateNumericExpectation(value,operation);
        var bounds=operation.equals("between")?List.of(value.path("min"),value.path("max")):List.of(value);
        for(var bound:bounds) { var count=number(bound); if(count.stripTrailingZeros().scale()>0 || count.signum()<0 || count.compareTo(BigDecimal.valueOf(10000))>0) throw new IllegalArgumentException("Invalid count target"); }
    }
    private void requireEvidence(EvaluatorDefinition definition,EvaluationContext context) {
        for(String need:definition.requiredEvidence()) {
            boolean missing=switch(need) { case "output" -> context.output().isMissingNode(); case "expected" -> context.expected().isMissingNode(); case "task_input" -> context.taskInput().isMissingNode(); case "events","trace" -> context.completeness()==EvidenceCompleteness.NONE || context.completeness()==EvidenceCompleteness.PENDING; case "artifacts" -> context.artifacts().isEmpty(); default -> true; };
            if(missing) throw new MissingEvidence();
        }
    }
    private JsonNode expected(EvaluationContext context,JsonNode config,Set<String> refs) {
        if(config.has("expected")==config.has("expected_selector")) throw new IllegalArgumentException("Specify exactly one expectation");
        if(config.has("expected")) return config.get("expected");
        var selection=selector.select(context,text(config,"expected_selector")); if(selection.value().isMissingNode()) throw new MissingEvidence(); refs.add(selection.reference()); return selection.value();
    }
    private boolean toolSet(EvaluationContext context,JsonNode config,Set<String> refs) {
        var requested=config.path("tools"); if(!requested.isArray() || requested.isEmpty() || requested.size()>64) throw new IllegalArgumentException("Invalid tool set");
        var wanted=new HashSet<String>(); for(var tool:requested) { if(!tool.isTextual() || tool.asText().isBlank() || tool.asText().length()>1024) throw new IllegalArgumentException("Invalid tool"); wanted.add(tool.asText()); }
        var calls=selector.tools(context,null); var actual=new HashSet<String>(); for(var call:calls) { actual.add(call.start().name()); refs.add("event:"+call.start().eventId()); }
        String mode=text(config,"mode");
        if(mode.equals("absent")) { if(actual.stream().anyMatch(wanted::contains)) return false; requireComplete(context); return true; }
        if(!Set.of("exact","contains_all").contains(mode)) throw new IllegalArgumentException("Invalid tool-set mode");
        if(calls.isEmpty()) throw new MissingEvidence(); boolean matches=mode.equals("exact")?actual.equals(wanted):actual.containsAll(wanted);
        if(!matches) requireComplete(context); return matches;
    }
    private boolean arguments(EvaluationContext context,JsonNode config,Set<String> refs) {
        var calls=selector.tools(context,text(config,"tool")); var results=new ArrayList<Boolean>(); var expected=expected(context,config,refs); String pointer=text(config,"selector"); String comparison=text(config,"comparison");
        for(var call:calls) {
            refs.add("event:"+call.start().eventId()); var value=selector.at(call.start().payload().path("input"),pointer);
            results.add(value.isMissingNode() || call.start().capture().redacted() || call.start().capture().truncated()?null:compare(value,expected,comparison));
        }
        return aggregate(context,results,config);
    }
    private boolean count(EvaluationContext context,JsonNode config,Set<String> refs) {
        requireComplete(context); var calls=selector.tools(context,text(config,"tool")); var expected=expected(context,config,refs); validateCountExpectation(expected,text(config,"comparison"));
        if(calls.isEmpty() && (!expected.isNumber() || number(expected).compareTo(BigDecimal.ZERO)!=0)) throw new MissingEvidence();
        calls.forEach(call -> refs.add("event:"+call.start().eventId())); return compare(IntNode.valueOf(calls.size()),expected,text(config,"comparison"));
    }
    private boolean duration(EvaluationContext context,JsonNode config,Set<String> refs) {
        var calls=selector.tools(context,text(config,"tool")); if(calls.isEmpty()) throw new MissingEvidence(); String aggregation=text(config,"aggregation"),comparison=text(config,"comparison"); var expected=expected(context,config,refs);
        var values=new ArrayList<BigDecimal>();
        for(var call:calls) { if(call.end()==null || !call.end().payload().has("duration_ms")) throw new MissingEvidence(); var value=number(call.end().payload().get("duration_ms")); if(value.signum()<0) throw new IllegalArgumentException("Negative duration"); values.add(value); refs.add("event:"+call.end().eventId()); }
        if(aggregation.equals("any") || aggregation.equals("all")) return aggregate(context,values.stream().map(value -> compare(DecimalNode.valueOf(value),expected,comparison)).toList(),config);
        requireComplete(context);
        BigDecimal value=switch(aggregation) { case "sum" -> values.stream().reduce(BigDecimal.ZERO,BigDecimal::add); case "min" -> values.stream().min(BigDecimal::compareTo).orElseThrow(); case "max" -> values.stream().max(BigDecimal::compareTo).orElseThrow(); default -> throw new IllegalArgumentException("Invalid duration aggregation"); };
        return compare(DecimalNode.valueOf(value),expected,comparison);
    }
    private boolean causal(EvaluationContext context,JsonNode config,Set<String> refs) {
        var before=selector.tools(context,text(config,"before")); var after=selector.tools(context,text(config,"after")); if(before.isEmpty() || after.isEmpty()) throw new MissingEvidence();
        String relation=text(config,"relation"); if(!Set.of("ancestor","dependency").contains(relation)) throw new IllegalArgumentException("Invalid causal relation");
        var starts=selector.starts(context); var targets=new HashSet<>(before.stream().map(call -> call.start().spanId()).toList());
        var terminal=new HashMap<String,TraceEvent>(); for(var event:context.events()) if(event.eventType()==EventType.SPAN_COMPLETED || event.eventType()==EventType.SPAN_FAILED) terminal.put(event.spanId(),event);
        var results=new ArrayList<Boolean>(); var work=new int[]{0};
        for(var call:after) {
            boolean matched=causalPath(call.start().spanId(),targets,relation,starts,terminal,refs,work); boolean reversed=false;
            if(!matched) for(var earlier:before) if(causalPath(earlier.start().spanId(),Set.of(call.start().spanId()),relation,starts,terminal,refs,work)) { reversed=true; break; }
            refs.add("event:"+call.start().eventId()); results.add(matched?Boolean.TRUE:reversed?Boolean.FALSE:null);
        }
        return aggregate(context,results,config);
    }
    private boolean causalPath(String from,Set<String> targets,String relation,Map<String,TraceEvent> starts,Map<String,TraceEvent> terminal,Set<String> refs,int[] work) {
        String current=from; var visited=new HashSet<String>(); boolean matched=false;
        while(current!=null) {
            if(!visited.add(current) || visited.size()>128 || ++work[0]>4096) throw new IllegalArgumentException("Causal graph complexity limit");
            var event=starts.get(current); if(event==null) return false;
            String next=relation.equals("ancestor")?event.parentSpanId():event.payload().path("input").path("depends_on_span_id").asText(null);
            if(next!=null) {
                var predecessor=starts.get(next); if(predecessor==null || predecessor.sequence()>=event.sequence()) return false;
                if(relation.equals("dependency")) {
                    var end=terminal.get(next); if(end==null || end.sequence()>=event.sequence() || end.sequence()<=predecessor.sequence()) return false;
                    refs.add("event:"+end.eventId());
                }
                refs.add("event:"+predecessor.eventId()); if(targets.contains(next)) matched=true;
            }
            current=next;
        }
        return matched;
    }
    private boolean aggregate(EvaluationContext context,List<Boolean> results,JsonNode config) {
        String aggregation=text(config,"aggregation"); if(!Set.of("any","all","count").contains(aggregation)) throw new IllegalArgumentException("Invalid aggregation");
        if(results.isEmpty()) throw new MissingEvidence(); boolean unknown=results.stream().anyMatch(Objects::isNull);
        if(aggregation.equals("any") && results.contains(Boolean.TRUE)) return true;
        if(aggregation.equals("all") && results.contains(Boolean.FALSE)) return false;
        if(unknown) throw new MissingEvidence(); requireComplete(context);
        if(aggregation.equals("any")) return false; if(aggregation.equals("all")) return true;
        var target=number(config.path("match_count")); if(target.stripTrailingZeros().scale()>0 || target.signum()<0) throw new IllegalArgumentException("Invalid match count");
        return BigDecimal.valueOf(results.stream().filter(Boolean.TRUE::equals).count()).compareTo(target)==0;
    }
    static boolean equal(JsonNode actual,JsonNode expected) { guardComparable(actual,0,new int[]{0}); guardComparable(expected,0,new int[]{0}); return equalValue(actual,expected); }
    private static boolean equalValue(JsonNode actual,JsonNode expected) {
        if(actual.isNumber() && expected.isNumber()) return number(actual).compareTo(number(expected))==0;
        if(actual.isObject() && expected.isObject()) { if(actual.size()!=expected.size()) return false; for(var fields=actual.fields();fields.hasNext();) { var field=fields.next(); if(!expected.has(field.getKey()) || !equalValue(field.getValue(),expected.get(field.getKey()))) return false; } return true; }
        if(actual.isArray() && expected.isArray()) { if(actual.size()!=expected.size()) return false; for(int i=0;i<actual.size();i++) if(!equalValue(actual.get(i),expected.get(i))) return false; return true; }
        return actual.equals(expected);
    }
    private static void guardComparable(JsonNode value,int depth,int[] count) { if(depth>32 || ++count[0]>4096) throw new IllegalArgumentException("Comparison complexity limit"); if(value.isNumber()) number(value); if(value.isContainerNode()) value.forEach(child -> guardComparable(child,depth+1,count)); }
    private boolean compare(JsonNode actual,JsonNode expected,String operation) {
        if(operation.equals("eq")) return equal(actual,expected); if(operation.equals("ne")) return !equal(actual,expected);
        if(!Set.of("gt","gte","lt","lte","between").contains(operation)) throw new IllegalArgumentException("Unsupported comparison");
        BigDecimal min,max;
        if(operation.equals("between")) { if(!expected.isObject() || expected.size()!=2) throw new IllegalArgumentException("Invalid range"); min=number(expected.path("min")); max=number(expected.path("max")); if(min.compareTo(max)>0) throw new IllegalArgumentException("Invalid range"); }
        else { min=number(expected); max=min; }
        if(!actual.isNumber()) return false; var value=number(actual);
        return switch(operation) { case "gt" -> value.compareTo(min)>0; case "gte" -> value.compareTo(min)>=0; case "lt" -> value.compareTo(min)<0; case "lte" -> value.compareTo(min)<=0; default -> value.compareTo(min)>=0 && value.compareTo(max)<=0; };
    }
    static BigDecimal number(JsonNode value) { if(!value.isNumber()) throw new IllegalArgumentException("Expected finite JSON number"); var number=value.decimalValue(); if(number.precision()>128 || Math.abs((long)number.scale())>128) throw new IllegalArgumentException("Numeric complexity limit"); return number; }
    private String text(JsonNode config,String key) { var value=config.path(key); if(!value.isTextual() || value.asText().isBlank() || value.asText().length()>1024) throw new IllegalArgumentException("Missing or invalid rule field"); return value.asText(); }
    private void requireComplete(EvaluationContext context) { if(context.completeness()!=EvidenceCompleteness.COMPLETE) throw new MissingEvidence(); }
    private CriterionResult unscored(EvaluatorDefinition definition,CriterionStatus status,String reason) { return new CriterionResult(definition.criterionId(),status,null,null,reason,List.of(),definition.evaluatorVersion()); }
    private static final class MissingEvidence extends RuntimeException {}
}
