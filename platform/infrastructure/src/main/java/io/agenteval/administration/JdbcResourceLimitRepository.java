package io.agenteval.administration;

import io.agenteval.identity.*;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcResourceLimitRepository implements ResourceLimitService.Repository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    public JdbcResourceLimitRepository(JdbcClient jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc; transactions=new TransactionTemplate(manager); }
    public ResourceLimitService.View get(UUID workspace) {
        return transactions.execute(status -> {
            var limits=JdbcResourceAdmission.limits(jdbc,workspace,false);
            var usage=jdbc.sql("select trial_executions,evaluation_jobs,python_reserved_seconds,artifact_bytes,evidence_bytes from workspace_resource_usage where workspace_id=?").param(workspace)
                .query((r,n) -> new ResourceUsage(r.getLong("trial_executions"),r.getLong("evaluation_jobs"),r.getLong("python_reserved_seconds"),r.getLong("artifact_bytes"),r.getLong("evidence_bytes"))).optional().orElseThrow(ScopeViolationException::new);
            return new ResourceLimitService.View(limits,usage);
        });
    }
    public void configure(WorkspaceActor actor,UUID workspace,ResourceLimits limits,String reason) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single();
            jdbc.sql("select id from workspaces where id=? for update").param(workspace).query(UUID.class).optional().orElseThrow(ScopeViolationException::new);
            jdbc.sql("update workspace_resource_limits set max_online_workers=?,max_running_trials=?,max_stored_bytes=?,max_experiments=?,max_catalog_resources=?,max_published_versions=?,max_trial_executions=?,max_evaluation_jobs=?,max_python_seconds=? where workspace_id=?")
                .params(limits.maxOnlineWorkers(),limits.maxRunningTrials(),limits.maxStoredBytes(),limits.maxExperiments(),limits.maxCatalogResources(),limits.maxPublishedVersions(),limits.maxTrialExecutions(),limits.maxEvaluationJobs(),limits.maxPythonSeconds(),workspace).update();
            jdbc.sql("update grading_jobs g set status='QUEUED',next_run_at=g.created_at where g.workspace_id=? and g.status='WAITING_RESOURCES' and exists(select 1 from trials t join experiments e on e.id=t.experiment_id where t.id=g.trial_id and e.status not in ('CANCELLED','DELETING','DELETED'))").param(workspace).update();
            jdbc.sql("update evaluation_jobs j set status='QUEUED',wait_reason=null,next_run_at=j.created_at where j.workspace_id=? and j.status='DEFERRED' and j.wait_reason='PYTHON_RESOURCE_LIMIT' and exists(select 1 from grading_jobs g where g.id=j.run_id and g.status<>'CANCELLED')").param(workspace).update();
            JdbcAuditWriter.append(jdbc,new WorkspaceActor(actor.userId(),workspace,true),AuditAction.RESOURCE_LIMITS_CONFIGURED,workspace);
        });
    }
}
