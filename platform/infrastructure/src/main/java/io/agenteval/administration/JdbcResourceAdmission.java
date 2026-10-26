package io.agenteval.administration;

import io.agenteval.identity.ScopeViolationException;
import io.agenteval.workers.CapacityExceededException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Internal admissions join the caller's transaction; the limits row is the per-workspace budget lock. */
public final class JdbcResourceAdmission {
    private JdbcResourceAdmission() {}
    public static ResourceLimits limits(JdbcClient jdbc,UUID workspace,boolean lock) {
        return jdbc.sql("select * from workspace_resource_limits where workspace_id=?"+(lock?" for update":"")).param(workspace)
            .query((r,n) -> new ResourceLimits(r.getInt("max_online_workers"),r.getInt("max_running_trials"),r.getLong("max_stored_bytes"),r.getInt("max_experiments"),r.getInt("max_catalog_resources"),r.getInt("max_published_versions"),r.getLong("max_trial_executions"),r.getLong("max_evaluation_jobs"),r.getLong("max_python_seconds")))
            .optional().orElseThrow(ScopeViolationException::new);
    }
    public static void startExperiment(JdbcClient jdbc,UUID workspace,int trials) {
        var limits=limits(jdbc,workspace,true);
        long active=jdbc.sql("select count(*) from experiments where workspace_id=? and status not in ('DELETING','DELETED')").param(workspace).query(Long.class).single();
        if(active>=limits.maxExperiments() || jdbc.sql("update workspace_resource_usage set trial_executions=trial_executions+? where workspace_id=? and trial_executions<=?-?")
            .params(trials,workspace,limits.maxTrialExecutions(),trials).update()!=1) throw new CapacityExceededException();
    }
    public static void onlineWorker(JdbcClient jdbc,UUID workspace,UUID worker,Instant now) {
        var limits=limits(jdbc,workspace,true); var cutoff=Timestamp.from(now.minusSeconds(60));
        if(worker!=null && jdbc.sql("select exists(select 1 from worker_instances where id=? and workspace_id=? and last_seen_at>?)").params(worker,workspace,cutoff).query(Boolean.class).single()) return;
        long all=jdbc.sql("select count(*) from worker_instances w join worker_credentials c on c.id=w.credential_id where not c.revoked and w.last_seen_at>?").param(cutoff).query(Long.class).single();
        long owned=jdbc.sql("select count(*) from worker_instances w join worker_credentials c on c.id=w.credential_id where w.workspace_id=? and not c.revoked and w.last_seen_at>?").params(workspace,cutoff).query(Long.class).single();
        if(all>=50 || owned>=limits.maxOnlineWorkers()) throw new CapacityExceededException();
    }
    public static boolean evaluationJobs(JdbcClient jdbc,UUID workspace,int count) {
        if(count<0 || count>100) throw new IllegalArgumentException("Invalid evaluation admission");
        var limits=limits(jdbc,workspace,true);
        return jdbc.sql("update workspace_resource_usage set evaluation_jobs=evaluation_jobs+? where workspace_id=? and evaluation_jobs<=?-?")
            .params(count,workspace,limits.maxEvaluationJobs(),count).update()==1;
    }
    public static boolean pythonExecution(JdbcClient jdbc,UUID workspace,UUID job) {
        var limits=limits(jdbc,workspace,true);
        if(jdbc.sql("select python_budget_reserved from evaluation_jobs where id=? and workspace_id=?").params(job,workspace).query(Boolean.class).single()) return true;
        if(jdbc.sql("update workspace_resource_usage set python_reserved_seconds=python_reserved_seconds+30 where workspace_id=? and python_reserved_seconds<=?-30").params(workspace,limits.maxPythonSeconds()).update()!=1) return false;
        jdbc.sql("update evaluation_jobs set python_budget_reserved=true where id=? and workspace_id=?").params(job,workspace).update(); return true;
    }
    public static void metadata(JdbcClient jdbc,UUID workspace,String table) {
        if(!java.util.Set.of("catalog_resources","catalog_versions","agents","worker_credentials").contains(table)) throw new IllegalArgumentException("Unknown resource category");
        var limits=limits(jdbc,workspace,true);
        long maximum=table.equals("catalog_versions")?limits.maxPublishedVersions():limits.maxCatalogResources();
        if(jdbc.sql("select count(*) from "+table+" where workspace_id=?").param(workspace).query(Long.class).single()>=maximum) throw new CapacityExceededException();
    }
    public static void artifact(JdbcClient jdbc,UUID workspace,long bytes) {
        var limits=limits(jdbc,workspace,true);
        if(bytes<0 || jdbc.sql("update workspace_resource_usage set artifact_bytes=artifact_bytes+? where workspace_id=? and artifact_bytes<=?-evidence_bytes-?").params(bytes,workspace,limits.maxStoredBytes(),bytes).update()!=1) throw new CapacityExceededException();
    }
    public static void evidence(JdbcClient jdbc,UUID workspace,long bytes) {
        var limits=limits(jdbc,workspace,true);
        if(bytes<0 || jdbc.sql("update workspace_resource_usage set evidence_bytes=evidence_bytes+? where workspace_id=? and evidence_bytes<=?-artifact_bytes-?").params(bytes,workspace,limits.maxStoredBytes(),bytes).update()!=1) throw new CapacityExceededException();
    }
}
