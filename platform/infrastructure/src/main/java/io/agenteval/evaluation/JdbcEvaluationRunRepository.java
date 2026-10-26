package io.agenteval.evaluation;
import io.agenteval.identity.*;
import io.agenteval.protocol.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
@Repository
public class JdbcEvaluationRunRepository implements EvaluationRunRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ProtocolCodec codec=new ProtocolCodec();
    public JdbcEvaluationRunRepository(JdbcClient jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc; transactions=new TransactionTemplate(manager); transactions.setReadOnly(true); transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public EvaluationRunView get(WorkspaceActor actor,UUID runId) {
        return transactions.execute(status -> {
            var header=jdbc.sql("select g.*,s.content_hash from grading_jobs g join evidence_snapshots s on s.id=g.snapshot_id join trials t on t.id=g.trial_id join experiments e on e.id=t.experiment_id where g.id=? and g.workspace_id=? and e.status not in ('DELETING','DELETED')").params(runId,actor.workspaceId())
                .query((row,index)->new EvaluationRunView(runId,row.getObject("trial_id",UUID.class),row.getObject("snapshot_id",UUID.class),row.getString("content_hash"),row.getObject("suite_version_id",UUID.class),row.getString("purpose"),row.getString("status"),List.of(),row.getTimestamp("created_at").toInstant())).optional().orElseThrow(ScopeViolationException::new);
            var results=jdbc.sql("select j.id,coalesce(r.payload,j.last_result) payload from evaluation_jobs j left join evaluation_results r on r.job_id=j.id where j.run_id=? and j.workspace_id=? and coalesce(r.payload,j.last_result) is not null order by j.created_at,j.id limit 100").params(runId,actor.workspaceId()).query((row,index)->{
                try { return new EvaluationRunView.Criterion(row.getObject("id",UUID.class),codec.decode(row.getString("payload"),CriterionResult.class)); }
                catch(java.io.IOException invalid) { throw new IllegalStateException("Invalid persisted criterion",invalid); }
            }).list();
            var reasons=new ArrayList<>(jdbc.sql("select distinct wait_reason from evaluation_jobs where run_id=? and workspace_id=? and status in ('DEFERRED','WAITING_QUOTA') and wait_reason is not null order by wait_reason").params(runId,actor.workspaceId()).query(String.class).list());
            if(header.status().equals("WAITING_RESOURCES")) reasons.add("EVALUATION_JOB_LIMIT");
            return new EvaluationRunView(header.runId(),header.trialId(),header.snapshotId(),header.snapshotHash(),header.suiteVersionId(),header.purpose(),header.status(),results,header.createdAt(),reasons);
        });
    }
}
