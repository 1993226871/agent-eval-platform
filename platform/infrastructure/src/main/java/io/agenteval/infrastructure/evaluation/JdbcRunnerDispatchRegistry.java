package io.agenteval.infrastructure.evaluation;

import io.agenteval.evaluation.EvaluatorJob;
import io.agenteval.experiments.AttemptRejectedException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcRunnerDispatchRegistry {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    public JdbcRunnerDispatchRegistry(JdbcClient jdbc,PlatformTransactionManager manager,Clock clock) { this.jdbc=jdbc; tx=new TransactionTemplate(manager); this.clock=clock; }
    public void track(EvaluatorJob job) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single();
            UUID experiment=jdbc.sql("""
                select t.experiment_id from evaluation_jobs j join grading_jobs g on g.id=j.run_id
                join trials t on t.id=j.trial_id join experiments e on e.id=t.experiment_id
                where j.id=? and j.workspace_id=? and j.status='RUNNING' and j.lease_token=? and j.lease_expires_at>?
                  and j.definition->>'type'='PYTHON' and g.status<>'CANCELLED' and e.status not in ('CANCELLED','DELETING','DELETED')
                """).params(job.id(),job.owner().workspaceId(),job.leaseToken(),Timestamp.from(clock.instant())).query(UUID.class).optional().orElseThrow(AttemptRejectedException::new);
            jdbc.sql("insert into runner_executions(job_id,experiment_id,workspace_id) values(?,?,?) on conflict(job_id) do nothing").params(job.id(),experiment,job.owner().workspaceId()).update();
        });
    }
}
