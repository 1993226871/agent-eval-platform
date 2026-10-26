package io.agenteval.reporting;
import io.agenteval.evaluation.*;
import io.agenteval.identity.WorkspaceActor;
import io.agenteval.protocol.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class ReportService {
    private final ReportRepository repository;
    private final EvaluationAggregator aggregator;
    public ReportService(ReportRepository repository,EvaluationAggregator aggregator) { this.repository=repository; this.aggregator=aggregator; }
    public ExperimentReport get(WorkspaceActor actor,UUID experimentId) {
        return render(repository.load(actor,experimentId));
    }
    ExperimentReport render(ReportData data) {
        UUID experimentId=data.experimentId(); int passed=0,failed=0,unknown=0,pending=0,cancelled=0,started=0,finished=0; var executions=new TreeMap<String,Integer>(); var durations=new ArrayList<Long>();
        for(var trial:data.trials()) {
            String verdict=trial(data,trial).verdict(); switch(verdict) { case "PASS" -> passed++; case "FAIL" -> failed++; case "CANCELLED" -> cancelled++; case "PENDING" -> pending++; default -> unknown++; }
            executions.merge(trial.executionStatus().name(),1,Integer::sum); if(trial.startedAt()!=null) started++;
            if(!Set.of(TrialExecutionStatus.QUEUED,TrialExecutionStatus.LEASED,TrialExecutionStatus.RUNNING).contains(trial.executionStatus())) finished++;
            if(trial.durationMs()!=null && trial.durationMs()>=0) durations.add(trial.durationMs());
        }
        durations.sort(Long::compareTo); var efficiency=new ExperimentReport.Efficiency(durations.size(),percentile(durations,0.5),percentile(durations,0.95),"worker_reported");
        return new ExperimentReport(experimentId,data.datasetVersionId(),data.suiteVersionId(),data.agentVersion(),data.experimentStatus(),data.trials().size(),started,finished,passed+failed,passed,failed,unknown,pending,cancelled,
            ExperimentReport.Rate.of(passed,data.trials().size()),ExperimentReport.Rate.of(passed,passed+failed),Map.copyOf(executions),aggregator.dimensionsForAttempts(data.trials().stream().map(row->new EvaluationAggregator.Attempt(entries(data,row),row.results())).toList()),efficiency,
            data.trials().stream().map(row->row.suiteVersionId()==null?data.suiteVersionId():row.suiteVersionId()).distinct().sorted().toList());
    }
    public Page trials(WorkspaceActor actor,UUID experimentId,String cursor,int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("Invalid page size"); var data=repository.load(actor,experimentId); int offset=0;
        if(cursor!=null) {
            try { if(cursor.length()>512) throw new IllegalArgumentException(); var fields=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
                if(fields.length!=3 || !fields[0].equals(actor.workspaceId().toString()) || !fields[1].equals(experimentId.toString())) throw new IllegalArgumentException(); offset=Integer.parseInt(fields[2]); if(offset<0 || offset>data.trials().size()) throw new IllegalArgumentException();
            } catch(IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid report cursor"); }
        }
        int end=Math.min(data.trials().size(),offset+limit); var items=data.trials().subList(offset,end).stream().map(row -> trial(data,row)).toList();
        String next=end<data.trials().size()?Base64.getUrlEncoder().withoutPadding().encodeToString((actor.workspaceId()+"|"+experimentId+"|"+end).getBytes(StandardCharsets.UTF_8)):null; return new Page(items,next);
    }
    TrialReport trial(ReportData data,ReportData.TrialEvaluation row) {
        boolean pending=row.runId()==null || Set.of("QUEUED","PENDING","RUNNING","WAITING_QUOTA").contains(row.evaluationState()); var aggregate=aggregator.aggregate(entries(data,row),row.results(),pending);
        String verdict=row.executionStatus()==TrialExecutionStatus.CANCELLED?"CANCELLED":aggregate.verdict();
        return new TrialReport(row.trialId(),row.caseIndex(),row.attempt(),row.executionStatus(),row.runId(),row.snapshotId(),row.evaluationState(),verdict,aggregate.dimensions(),row.results().values().stream().sorted(Comparator.comparing(CriterionResult::criterionId).thenComparing(CriterionResult::evaluatorVersion)).toList(),row.durationMs(),row.suiteVersionId()==null?data.suiteVersionId():row.suiteVersionId());
    }
    private List<EvaluationEntry> entries(ReportData data,ReportData.TrialEvaluation row) { return row.entries().isEmpty()?data.entries():row.entries(); }
    private Long percentile(List<Long> values,double probability) { return values.isEmpty()?null:values.get(Math.max(0,(int)Math.ceil(values.size()*probability)-1)); }
    public record Page(List<TrialReport> items,String nextCursor) { public Page { items=List.copyOf(items); } }
}
