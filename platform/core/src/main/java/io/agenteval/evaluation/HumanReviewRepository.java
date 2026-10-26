package io.agenteval.evaluation;
import io.agenteval.identity.WorkspaceActor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
public interface HumanReviewRepository {
    HumanReview add(WorkspaceActor actor,UUID resultId,BigDecimal score,String reason,Instant now);
    List<HumanReview> list(WorkspaceActor actor,UUID resultId,int limit);
}
