package io.agenteval.reporting;

import io.agenteval.evaluation.*;
import io.agenteval.identity.WorkspaceActor;
import java.util.*;

public final class ReportExporter {
    private final ComparisonQueries queries;
    private final ReportService reports;
    public ReportExporter(ComparisonQueries queries, EvaluationAggregator aggregator) { this.queries=queries; reports=new ReportService(queries::load,aggregator); }
    public Export export(WorkspaceActor actor, UUID experiment) {
        var input=queries.export(actor,experiment); var data=input.input().data();
        return new Export("1",reports.render(data),input.input().provenance(),data.trials().stream().map(row -> reports.trial(data,row)).toList(),input.reviews(),input.evidence(),input.resultIdentities(),"worker_reported; automatic scores and appended human reviews are separate");
    }
    public record Export(String exportVersion, ExperimentReport report, ReportProvenance provenance, List<TrialReport> trials,
        List<HumanReview> reviews, List<ComparisonQueries.FrozenEvidence> evidence, List<ComparisonQueries.ResultIdentity> resultIdentities, String source) {
        public Export { trials=List.copyOf(trials); reviews=List.copyOf(reviews); evidence=List.copyOf(evidence); resultIdentities=List.copyOf(resultIdentities); }
    }
}
