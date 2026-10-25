package io.agenteval.evaluation;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
public record EvaluatorDefinition(UUID evaluatorVersion,String criterionId,String type,List<String> requiredEvidence,BigDecimal threshold,JsonNode config) {
    public EvaluatorDefinition { requiredEvidence=List.copyOf(requiredEvidence); config=config.deepCopy(); }
    @Override public JsonNode config() { return config.deepCopy(); }
}
