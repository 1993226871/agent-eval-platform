package io.agenteval.evaluation;
import java.math.BigDecimal;
import java.util.UUID;
public record EvaluationEntry(UUID evaluatorVersion,String dimension,boolean requiredToPass,BigDecimal weight) {}
