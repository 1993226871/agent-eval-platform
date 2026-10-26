package io.agenteval.evaluation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record HumanReview(UUID id,UUID resultId,BigDecimal score,String reason,UUID reviewerId,Instant createdAt) {}
