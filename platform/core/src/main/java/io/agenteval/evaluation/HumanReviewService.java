package io.agenteval.evaluation;
import io.agenteval.identity.WorkspaceActor;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
public final class HumanReviewService {
    private final HumanReviewRepository repository;
    private final Clock clock;
    public HumanReviewService(HumanReviewRepository repository,Clock clock) { this.repository=repository; this.clock=clock; }
    public HumanReview add(WorkspaceActor actor,UUID resultId,BigDecimal score,String reason) {
        if(resultId==null || score==null || score.compareTo(BigDecimal.ZERO)<0 || score.compareTo(BigDecimal.ONE)>0 || Math.abs((long)score.scale())>6 || score.precision()>8 || reason==null || reason.isBlank() || reason.length()>2048)
            throw new IllegalArgumentException("Invalid human review");
        return repository.add(actor,resultId,score,reason,clock.instant());
    }
    public List<HumanReview> list(WorkspaceActor actor,UUID resultId,int limit) {
        if(resultId==null || limit<1 || limit>100) throw new IllegalArgumentException("Invalid review query");
        return repository.list(actor,resultId,limit);
    }
}
