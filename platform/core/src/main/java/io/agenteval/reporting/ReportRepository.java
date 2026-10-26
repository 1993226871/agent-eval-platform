package io.agenteval.reporting;
import io.agenteval.identity.WorkspaceActor;
import java.util.UUID;
public interface ReportRepository {
    ReportData load(WorkspaceActor actor,UUID experimentId);
    default ReportData summary(WorkspaceActor actor,UUID experimentId) { return load(actor,experimentId); }
    default PageData page(WorkspaceActor actor,UUID experimentId,int offset,int limit) {
        var data=load(actor,experimentId);
        if(offset<0 || offset>data.trials().size()) throw new IllegalArgumentException("Invalid report cursor");
        var subset=new ReportData(data.experimentId(),data.datasetVersionId(),data.suiteVersionId(),data.agentVersion(),data.experimentStatus(),data.entries(),data.trials().subList(offset,Math.min(offset+limit,data.trials().size())));
        return new PageData(subset,data.trials().size());
    }
    record PageData(ReportData data,int total) {}
}
