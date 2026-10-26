package io.agenteval.evaluation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.agenteval.model.*;
import io.agenteval.protocol.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

public final class LlmEvaluator implements Evaluator {
    private final Function<JudgeRequest,JudgeResponse> gateway;
    private final JudgePromptBuilder prompts=new JudgePromptBuilder();
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public LlmEvaluator(ModelGateway gateway) { this(gateway::judge); }
    LlmEvaluator(Function<JudgeRequest,JudgeResponse> gateway) { this.gateway=gateway; }
    @Override public CriterionResult evaluate(EvaluatorDefinition definition,EvaluationContext context) {
        return unscored(definition,CriterionStatus.ERROR,"LLM evaluation requires a durable job and workspace identity");
    }
    @Override public CriterionResult evaluate(EvaluatorJob job,EvaluationContext context) {
        var definition=job.definition();
        if(!job.trialId().equals(context.trialId()) || !job.snapshotId().equals(context.snapshotId())) throw new IllegalArgumentException("Foreign Judge evidence");
        try {
            var config=definition.config(); prompts.validate(definition);
            for(int attempt=0;attempt<2;attempt++) {
                var prompt=prompts.build(definition,context,attempt==1);
                UUID callId=UUID.nameUUIDFromBytes(("agent-eval:judge:"+job.id()+":"+attempt).getBytes(StandardCharsets.UTF_8));
                var request=new JudgeRequest(job.owner().workspaceId(),callId,prompts.id(config,"model_profile_id"),prompts.id(config,"price_version_id"),prompt.system(),prompt.data(),config.path("max_output_tokens").asInt());
                var response=gateway.apply(request);
                if(!response.callId().equals(callId) || !response.profileId().equals(request.modelProfileId()) || !response.priceVersionId().equals(request.priceVersionId())) return unscored(definition,CriterionStatus.ERROR,"MODEL_CALL_IDENTITY_MISMATCH");
                switch(response.status()) {
                    case "WAITING_QUOTA" -> { return unscored(definition,CriterionStatus.WAITING_QUOTA,"Model call "+callId+" awaits cumulative credits"); }
                    case "RESERVED","SEND_INTENT","PENDING_RECONCILIATION","WAITING_CAPACITY" -> throw new EvaluationDeferredException("MODEL_CALL_"+response.status());
                    case "SUCCEEDED","RECONCILED" -> { /* Parse only an existing accountable response. */ }
                    default -> { return unscored(definition,CriterionStatus.ERROR,"Model call "+callId+" ended with "+response.status()); }
                }
                if(response.status().equals("RECONCILED") && response.content()==null) return unscored(definition,CriterionStatus.ERROR,"MODEL_RESPONSE_UNAVAILABLE_AFTER_RECONCILIATION");
                try { return parse(definition,context,prompt,response.content()); }
                catch(IllegalArgumentException invalid) { if(attempt==1) return unscored(definition,CriterionStatus.ERROR,"INVALID_JUDGE_RESPONSE_AFTER_TWO_ACCOUNTED_CALLS"); }
            }
            throw new IllegalStateException("Unreachable Judge retry state");
        } catch(JudgePromptBuilder.EvidenceUnavailable missing) { return unscored(definition,CriterionStatus.INSUFFICIENT_EVIDENCE,missing.getMessage()); }
        catch(IllegalArgumentException invalid) { return unscored(definition,CriterionStatus.ERROR,"INVALID_JUDGE_CONFIGURATION_OR_CONTEXT"); }
    }
    private CriterionResult parse(EvaluatorDefinition definition,EvaluationContext context,JudgePromptBuilder.Prompt prompt,String content) {
        if(content==null || content.getBytes(StandardCharsets.UTF_8).length>65536) throw new IllegalArgumentException("Judge result limit");
        try {
            var raw=json.readTree(content); if(raw==null || !raw.isObject()) throw new IllegalArgumentException("Invalid Judge object");
            var keys=new HashSet<String>(); raw.fieldNames().forEachRemaining(keys::add);
            if(!keys.equals(Set.of("status","grade","reason","evidence_refs"))) throw new IllegalArgumentException("Invalid Judge fields");
            String state=raw.path("status").asText(); var reason=raw.path("reason");
            if(!Set.of("SCORED","INSUFFICIENT_EVIDENCE").contains(state) || !reason.isTextual() || reason.asText().isBlank() || reason.asText().length()>2048 || !raw.path("evidence_refs").isArray() || raw.path("evidence_refs").size()>1000) throw new IllegalArgumentException("Invalid Judge result");
            var refs=new ArrayList<String>();
            for(var reference:raw.path("evidence_refs")) {
                if(!reference.isTextual() || !prompt.allowedReferences().contains(reference.asText())) throw new IllegalArgumentException("Unprovided Judge evidence");
                refs.add(reference.asText());
            }
            new EvidenceReferenceValidator().validate(context,refs);
            if(state.equals("INSUFFICIENT_EVIDENCE")) {
                if(!raw.path("grade").isNull()) throw new IllegalArgumentException("Insufficient evidence cannot carry grade");
                return new CriterionResult(definition.criterionId(),CriterionStatus.INSUFFICIENT_EVIDENCE,null,null,reason.asText(),refs,definition.evaluatorVersion());
            }
            var grade=raw.path("grade");
            if(refs.isEmpty() || !grade.isIntegralNumber() || !grade.canConvertToInt() || grade.asInt()<0 || grade.asInt()>4) throw new IllegalArgumentException("Invalid rubric grade");
            BigDecimal score=BigDecimal.valueOf(grade.asInt()).divide(BigDecimal.valueOf(4));
            return new CriterionResult(definition.criterionId(),CriterionStatus.SCORED,score,score.compareTo(definition.threshold())>=0,reason.asText(),refs,definition.evaluatorVersion());
        } catch(java.io.IOException malformed) { throw new IllegalArgumentException("Invalid Judge JSON"); }
    }
    private CriterionResult unscored(EvaluatorDefinition definition,CriterionStatus status,String reason) { return new CriterionResult(definition.criterionId(),status,null,null,reason,List.of(),definition.evaluatorVersion()); }
}
