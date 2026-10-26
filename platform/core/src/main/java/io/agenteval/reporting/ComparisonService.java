package io.agenteval.reporting;

import io.agenteval.evaluation.EvaluationAggregator;
import io.agenteval.identity.WorkspaceActor;
import java.math.BigDecimal;
import java.util.*;

public final class ComparisonService {
    private final ComparisonQueries queries;
    private final ReportService reports;
    public ComparisonService(ComparisonQueries queries, EvaluationAggregator aggregator) { this.queries=queries; reports=new ReportService(queries::load,aggregator); }
    public ComparisonReport compare(WorkspaceActor actor, UUID leftId, UUID rightId) {
        var pair=queries.pair(actor,leftId,rightId);
        var left=reports.render(pair.left().data()); var right=reports.render(pair.right().data());
        var lp=pair.left().provenance(); var rp=pair.right().provenance(); var reasons=new ArrayList<String>();
        if(!left.datasetVersionId().equals(right.datasetVersionId())) reasons.add("DATASET_VERSION_MISMATCH");
        if(!left.evaluationSuiteVersionIds().equals(right.evaluationSuiteVersionIds())) reasons.add("SUITE_VERSION_MISMATCH");
        if(left.evaluationSuiteVersionIds().size()!=1 || right.evaluationSuiteVersionIds().size()!=1) reasons.add("MIXED_EVALUATION_SUITES");
        if(!lp.evaluatorVersions().equals(rp.evaluatorVersions())) reasons.add("EVALUATOR_VERSION_MISMATCH");
        if(!lp.modelPriceBindings().equals(rp.modelPriceBindings())) reasons.add("MODEL_PRICE_VERSION_MISMATCH");
        if(!evidenceSignatures(lp).equals(evidenceSignatures(rp))) reasons.add("EVIDENCE_SCOPE_MISMATCH");
        if(left.plannedAttempts()!=right.plannedAttempts()) reasons.add("ATTEMPT_PLAN_MISMATCH");
        if(left.pendingAttempts()>0 || right.pendingAttempts()>0 || left.finishedAttempts()!=left.plannedAttempts() || right.finishedAttempts()!=right.plannedAttempts()) reasons.add("EXECUTION_OR_EVALUATION_PENDING");
        if(left.determinableAttempts()==0 || right.determinableAttempts()==0) reasons.add("NO_DETERMINABLE_DATA");
        if(!coverage(left).equals(coverage(right))) reasons.add("COVERAGE_MISMATCH");
        var deltas=new TreeMap<String,BigDecimal>();
        if(reasons.isEmpty()) left.dimensions().forEach((dimension,value) -> {
            var other=right.dimensions().get(dimension);
            if(other!=null && other.score()!=null && value.score()!=null) deltas.put(dimension,other.score().subtract(value.score()));
        });
        return new ComparisonReport(left,right,lp,rp,reasons.isEmpty(),reasons,deltas);
    }
    private List<String> evidenceSignatures(ReportProvenance provenance) {
        return provenance.evidence().stream().map(e -> e.caseIndex()+"|"+e.attempt()+"|"+e.completeness()+"|"+e.scope()).sorted().toList();
    }
    private Map<String,String> coverage(ExperimentReport report) {
        var values=new TreeMap<String,String>(); report.dimensions().forEach((key,value) -> values.put(key,value.scoredCriteria()+"/"+value.plannedCriteria()+"|"+value.scoredWeight().stripTrailingZeros().toPlainString()+"/"+value.totalWeight().stripTrailingZeros().toPlainString())); return values;
    }
}
