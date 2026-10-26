package io.agenteval.experiments;

import io.agenteval.catalog.CatalogConflictException;
import io.agenteval.identity.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcExperimentDeletionRepository implements ExperimentDeletionRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    public JdbcExperimentDeletionRepository(JdbcClient jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc; transactions=new TransactionTemplate(manager); }
    private void scheduler() { jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single(); }
    @Override public ExperimentDeletionStatus status(WorkspaceActor actor,UUID experiment) {
        return jdbc.sql("select e.status,d.requested_at,d.next_cleanup_at,d.completed_at,d.failure_count from experiment_deletions d join experiments e on e.id=d.experiment_id and e.workspace_id=d.workspace_id where d.experiment_id=? and d.workspace_id=?")
            .params(experiment,actor.workspaceId()).query((row,n) -> new ExperimentDeletionStatus(experiment,row.getString("status"),row.getTimestamp("requested_at").toInstant(),row.getTimestamp("next_cleanup_at").toInstant(),row.getTimestamp("completed_at")==null?null:row.getTimestamp("completed_at").toInstant(),row.getInt("failure_count")))
            .optional().orElseThrow(ScopeViolationException::new);
    }
    @Override public void request(WorkspaceActor actor,UUID experiment,UUID key,Instant now) {
        transactions.executeWithoutResult(status -> {
            scheduler();
            jdbc.sql("select id from experiments where id=? and workspace_id=? for update").params(experiment,actor.workspaceId()).query(UUID.class).optional().orElseThrow(ScopeViolationException::new);
            var reused=jdbc.sql("select experiment_id from experiment_deletions where workspace_id=? and request_key=?").params(actor.workspaceId(),key).query(UUID.class).optional();
            if(reused.isPresent() && !reused.get().equals(experiment)) throw new CatalogConflictException();
            if(jdbc.sql("select count(*) from experiment_deletions where experiment_id=?").param(experiment).query(Long.class).single()>0) return;
            var notBefore=Timestamp.from(now.plusSeconds(300));
            jdbc.sql("insert into experiment_deletions(experiment_id,workspace_id,generation,request_key,requested_by,requested_at,not_before,next_cleanup_at) values(?,?,?,?,?,?,?,?)")
                .params(experiment,actor.workspaceId(),UUID.randomUUID(),key,actor.userId(),Timestamp.from(now),notBefore,notBefore).update();
            jdbc.sql("insert into deleted_artifact_objects(experiment_id,workspace_id,artifact_id,staging_key,sealed_key) select ?,a.workspace_id,a.id,a.staging_key,a.object_key from artifacts a join trials t on t.id=a.trial_id where t.experiment_id=? and a.workspace_id=?")
                .params(experiment,experiment,actor.workspaceId()).update();
            jdbc.sql("update experiments set status='DELETING' where id=? and workspace_id=?").params(experiment,actor.workspaceId()).update();
            jdbc.sql("update trials set status='CANCELLED',completed_at=coalesce(completed_at,?) where experiment_id=? and workspace_id=? and status in ('QUEUED','LEASED','RUNNING')")
                .params(Timestamp.from(now),experiment,actor.workspaceId()).update();
            jdbc.sql("update grading_jobs set status='CANCELLED',completed_at=coalesce(completed_at,?) where workspace_id=? and trial_id in(select id from trials where experiment_id=?) and status in ('QUEUED','RUNNING','WAITING_QUOTA','WAITING_RESOURCES')")
                .params(Timestamp.from(now),actor.workspaceId(),experiment).update();
            jdbc.sql("update evaluation_jobs set status='CANCELLED',completed_at=coalesce(completed_at,?),lease_token=null,lease_expires_at=null where workspace_id=? and trial_id in(select id from trials where experiment_id=?) and status in ('QUEUED','RUNNING','WAITING_QUOTA','DEFERRED')")
                .params(Timestamp.from(now),actor.workspaceId(),experiment).update();
            io.agenteval.administration.JdbcAuditWriter.append(jdbc,actor,io.agenteval.administration.AuditAction.EXPERIMENT_DELETION_REQUESTED,experiment);
        });
    }
    @Override public Optional<Cleanup> claim(UUID experiment,Instant now) {
        return transactions.execute(status -> {
            scheduler();
            var row=jdbc.sql("select workspace_id,generation from experiment_deletions where experiment_id=? and not_before<=? and next_cleanup_at<=? and (lease_until is null or lease_until<=?) for update")
                .params(experiment,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now)).query((r,n) -> Map.entry(r.getObject("workspace_id",UUID.class),r.getObject("generation",UUID.class))).optional();
            if(row.isEmpty()) return Optional.empty();
            UUID token=UUID.randomUUID();
            jdbc.sql("update experiment_deletions set cleanup_token=?,lease_until=? where experiment_id=?").params(token,Timestamp.from(now.plusSeconds(900)),experiment).update();
            var targets=jdbc.sql("select artifact_id,staging_key,sealed_key from deleted_artifact_objects where experiment_id=? and workspace_id=? order by cleaned_at nulls first,artifact_id limit 4")
                .params(experiment,row.get().getKey()).query((r,n) -> new ObjectTarget(r.getObject("artifact_id",UUID.class),r.getString("staging_key"),r.getString("sealed_key"))).list();
            return Optional.of(new Cleanup(experiment,row.get().getKey(),row.get().getValue(),token,targets));
        });
    }
    private boolean current(Cleanup cleanup,Instant now) {
        return jdbc.sql("select count(*) from experiment_deletions where experiment_id=? and workspace_id=? and generation=? and cleanup_token=? and lease_until>?")
            .params(cleanup.experimentId(),cleanup.workspaceId(),cleanup.generation(),cleanup.token(),Timestamp.from(now)).query(Long.class).single()==1;
    }
    @Override public boolean finish(Cleanup cleanup,Instant now) {
        return transactions.execute(status -> {
            // Same model -> scheduler ordering as gateway dispatch. Never delete objects while holding these locks.
            jdbc.sql("select id from model_gateway_guard where id=1 for update").query(Integer.class).single(); scheduler();
            if(!current(cleanup,now)) return false;
            for(var object:cleanup.objects()) jdbc.sql("update deleted_artifact_objects set cleaned_at=? where experiment_id=? and workspace_id=? and artifact_id=?")
                .params(Timestamp.from(now),cleanup.experimentId(),cleanup.workspaceId(),object.artifactId()).update();
            boolean remaining=jdbc.sql("select exists(select 1 from deleted_artifact_objects where experiment_id=? and cleaned_at is null)").param(cleanup.experimentId()).query(Boolean.class).single();
            if(!remaining) purge(cleanup,now);
            jdbc.sql("update experiment_deletions set cleanup_token=null,lease_until=null,next_cleanup_at=?,completed_at=case when ? then completed_at else coalesce(completed_at,?) end where experiment_id=?")
                .params(Timestamp.from(remaining?now:now.plusSeconds(3600)),remaining,Timestamp.from(now),cleanup.experimentId()).update();
            return !remaining;
        });
    }
    private void purge(Cleanup cleanup,Instant now) {
        UUID workspace=cleanup.workspaceId(),experiment=cleanup.experimentId();
        io.agenteval.administration.JdbcResourceAdmission.limits(jdbc,workspace,true);
        var released=jdbc.sql("""
            with selected as (select id from trials where experiment_id=? and workspace_id=?)
            select coalesce((select sum(a.size_bytes) from artifacts a join selected t on t.id=a.trial_id),0) artifact_bytes,
                coalesce((select sum(octet_length(e.event_json::text))*2 from trace_events e join selected t on t.id=e.trial_id),0)
                +coalesce((select sum(octet_length(s.manifest::text)) from evidence_snapshots s join selected t on t.id=s.trial_id),0) evidence_bytes
            """
        ).params(experiment,workspace).query((row,n) -> new long[]{row.getLong("artifact_bytes"),row.getLong("evidence_bytes")}).single();
        if(jdbc.sql("update workspace_resource_usage set artifact_bytes=artifact_bytes-?,evidence_bytes=evidence_bytes-? where workspace_id=? and artifact_bytes>=? and evidence_bytes>=?")
            .params(released[0],released[1],workspace,released[0],released[1]).update()!=1) throw new IllegalStateException("Storage accounting requires reconciliation");
        jdbc.sql("""
            update model_calls m set evidence_deleted=true,deleted_experiment_id=?,deleted_job_id=m.evaluation_job_id,evaluation_job_id=null
            where m.workspace_id=? and m.evaluation_job_id in(select j.id from evaluation_jobs j join trials t on t.id=j.trial_id where t.experiment_id=? and t.workspace_id=?)
            """
        ).params(experiment,workspace,experiment,workspace).update();
        jdbc.sql("delete from human_reviews where workspace_id=? and result_id in(select r.job_id from evaluation_results r join trials t on t.id=r.trial_id where t.experiment_id=?)").params(workspace,experiment).update();
        for(String table:List.of("evaluation_results","evaluator_previews","evaluation_jobs","grading_jobs","evidence_snapshots","artifacts","spans","trace_events","trial_leases","trial_evidence_usage")) {
            jdbc.sql("delete from "+table+" where workspace_id=? and trial_id in(select id from trials where experiment_id=? and workspace_id=?)").params(workspace,experiment,workspace).update();
        }
        jdbc.sql("delete from trials where experiment_id=? and workspace_id=?").params(experiment,workspace).update();
        if(jdbc.sql("update experiments set status='DELETED',snapshot='{}'::jsonb,agent_id=null,dataset_version_id=null,suite_version_id=null,agent_version='[deleted]' where id=? and workspace_id=? and status<>'DELETED'").params(experiment,workspace).update()==1)
            io.agenteval.administration.JdbcAuditWriter.system(jdbc,workspace,io.agenteval.administration.AuditAction.EXPERIMENT_DELETED,experiment);
        io.agenteval.catalog.EphemeralCatalogCleaner.clean(jdbc,experiment,workspace);
    }
    @Override public void failed(Cleanup cleanup,Instant now) {
        jdbc.sql("update experiment_deletions set cleanup_token=null,lease_until=null,next_cleanup_at=?,failure_count=failure_count+1 where experiment_id=? and workspace_id=? and generation=? and cleanup_token=?")
            .params(Timestamp.from(now.plusSeconds(30)),cleanup.experimentId(),cleanup.workspaceId(),cleanup.generation(),cleanup.token()).update();
    }
    @Override public List<UUID> ready(Instant now,int limit) {
        return jdbc.sql("select experiment_id from experiment_deletions where not_before<=? and next_cleanup_at<=? and (lease_until is null or lease_until<=?) order by next_cleanup_at,experiment_id limit ?")
            .params(Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),limit).query(UUID.class).list();
    }
}
