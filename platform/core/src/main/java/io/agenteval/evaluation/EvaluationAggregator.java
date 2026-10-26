package io.agenteval.evaluation;
import io.agenteval.protocol.*;
import java.math.*;
import java.util.*;
public final class EvaluationAggregator {
    public Aggregate aggregate(List<EvaluationEntry> entries,Map<UUID,CriterionResult> results,boolean pending) {
        var required=entries.stream().filter(EvaluationEntry::requiredToPass).toList(); String verdict;
        if(required.isEmpty()) verdict="UNDETERMINED";
        else if(required.stream().anyMatch(entry -> { var result=results.get(entry.evaluatorVersion()); return result!=null && result.status()==CriterionStatus.SCORED && Boolean.FALSE.equals(result.passed()); })) verdict="FAIL";
        else if(required.stream().anyMatch(entry -> { var result=results.get(entry.evaluatorVersion()); return result==null || result.status()!=CriterionStatus.SCORED; })) verdict=pending?"PENDING":"UNDETERMINED";
        else verdict="PASS";
        return new Aggregate(verdict,dimensions(entries,List.of(results)));
    }
    public Map<String,DimensionScore> dimensions(List<EvaluationEntry> entries,List<Map<UUID,CriterionResult>> attempts) {
        return dimensionsForAttempts(attempts.stream().map(results->new Attempt(entries,results)).toList());
    }
    public record Attempt(List<EvaluationEntry> entries,Map<UUID,CriterionResult> results) { public Attempt { entries=List.copyOf(entries); results=Map.copyOf(results); } }
    public Map<String,DimensionScore> dimensionsForAttempts(List<Attempt> attempts) {
        var scores=new LinkedHashMap<String,DimensionScore>();
        for(String dimension:List.of("outcome","process")) {
            BigDecimal total=BigDecimal.ZERO,scored=BigDecimal.ZERO,weighted=BigDecimal.ZERO; int plannedCriteria=0,scoredCriteria=0;
            for(var attempt:attempts) for(var entry:attempt.entries()) if(entry.dimension().equals(dimension)) {
                total=total.add(entry.weight()); plannedCriteria++; var result=attempt.results().get(entry.evaluatorVersion());
                if(result!=null && result.status()==CriterionStatus.SCORED) { scored=scored.add(entry.weight()); weighted=weighted.add(entry.weight().multiply(result.score())); scoredCriteria++; }
            }
            var score=scored.signum()==0?null:weighted.divide(scored,6,RoundingMode.HALF_UP);
            var coverage=total.signum()==0?null:scored.divide(total,6,RoundingMode.HALF_UP);
            scores.put(dimension,new DimensionScore(score,scored,total,coverage,scoredCriteria,plannedCriteria,scoredCriteria<plannedCriteria));
        }
        return Collections.unmodifiableMap(scores);
    }
    public record DimensionScore(BigDecimal score,BigDecimal scoredWeight,BigDecimal totalWeight,BigDecimal coverage,int scoredCriteria,int plannedCriteria,boolean partial) {}
    public record Aggregate(String verdict,Map<String,DimensionScore> dimensions) {}
}
