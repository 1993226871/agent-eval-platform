package io.agenteval.experiments;
import io.agenteval.catalog.CanonicalJson;
import io.agenteval.identity.ScopeViolationException;
import io.agenteval.protocol.*;
import io.agenteval.workers.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcLeaseRepository implements LeaseRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public JdbcLeaseRepository(JdbcClient jdbc,PlatformTransactionManager manager,ObjectMapper json) { this.jdbc=jdbc; tx=new TransactionTemplate(manager); this.json=json; }

    @Override public Optional<LeaseGrant> claim(WorkerPrincipal p,ClaimRequest request,Instant now) {
        return tx.execute(status -> {
            jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single();
            requireCredential(p);
            if(!p.agentIds().contains(request.agentId())) throw new ScopeViolationException();
            var worker=jdbc.sql("select max_concurrency from worker_instances where id=? and workspace_id=? and credential_id=? and agent_id=? and agent_version=?")
                .params(request.workerId(),p.workspaceId(),p.credentialId(),request.agentId(),request.agentVersion()).query(Integer.class).optional().orElseThrow(ScopeViolationException::new);
            jdbc.sql("update worker_instances set last_seen_at=? where id=?").params(Timestamp.from(now),request.workerId()).update();
            expireLost(now);
            if(activeCount(null,null)>=20 || activeCount(p.workspaceId(),null)>=2 || activeCount(null,request.workerId())>=worker) return Optional.empty();
            var candidate=jdbc.sql("select t.id,t.trace_id,t.task,e.deadline from trials t join experiments e on e.id=t.experiment_id where t.workspace_id=? and e.agent_id=? and e.agent_version=? and e.status='ACTIVE' and e.deadline>? and t.status='QUEUED' and t.next_run_at<=? order by t.created_at,t.id limit 1 for update of t,e skip locked")
                .params(p.workspaceId(),request.agentId(),request.agentVersion(),Timestamp.from(now),Timestamp.from(now))
                .query((row,n) -> new Candidate(row.getObject("id",UUID.class),row.getString("trace_id"),tree(row.getString("task")),row.getTimestamp("deadline").toInstant())).optional();
            if(candidate.isEmpty()) return Optional.empty();
            var task=candidate.get(); String token=TokenSecrets.generate();
            Instant runtimeLimit=now.plusSeconds(task.task().path("timeout_seconds").asLong(300));
            Instant deadline=min(task.deadline(),runtimeLimit); Instant expires=min(now.plusSeconds(60),deadline);
            jdbc.sql("insert into trial_leases(trial_id,workspace_id,worker_id,token_hash,expires_at,execution_deadline) values (?,?,?,?,?,?)")
                .params(task.id(),p.workspaceId(),request.workerId(),TokenSecrets.hash(token),Timestamp.from(expires),Timestamp.from(deadline)).update();
            jdbc.sql("update trials set status='LEASED' where id=? and status='QUEUED'").param(task.id()).update();
            var payload=new TrialContextPayload(task.id(),task.task().path("instruction").asText(),task.task().get("input"),deadline);
            return Optional.of(new LeaseGrant(task.id(),task.traceId(),token,expires,payload));
        });
    }

    @Override public AttemptContext validateAttempt(WorkerPrincipal p,UUID trialId,String token,Instant now) {
        return tx.execute(status -> { var row=lockAttempt(p,trialId,token); requireLive(row,now); return row.context(); });
    }
    @Override public AttemptContext validateUploadAttempt(WorkerPrincipal p,UUID trialId,String token,Instant now) {
        return tx.execute(status -> {
            var row=lockAttempt(p,trialId,token);
            if(row.completedAt()!=null) {
                if(!row.completedAt().plusSeconds(30).isAfter(now)) throw new AttemptRejectedException();
                return row.context();
            }
            requireLive(row,now); return row.context();
        });
    }
    @Override public void started(WorkerPrincipal p,UUID trialId,String token,Instant now) {
        tx.executeWithoutResult(status -> {
            var row=lockAttempt(p,trialId,token); requireLive(row,now);
            if(row.context().status()==TrialExecutionStatus.RUNNING) return;
            if(row.context().status()!=TrialExecutionStatus.LEASED || !row.context().experimentStatus().equals("ACTIVE")) throw new AttemptRejectedException();
            jdbc.sql("update trials set status='RUNNING',started_at=coalesce(started_at,?) where id=? and status='LEASED'").params(Timestamp.from(now),trialId).update();
        });
    }
    @Override public HeartbeatReply heartbeat(WorkerPrincipal p,UUID trialId,String token,Instant now) {
        return tx.execute(status -> {
            var row=lockAttempt(p,trialId,token);
            if(row.context().experimentStatus().equals("CANCELLED")) {
                jdbc.sql("update trials set status='CANCELLED',completed_at=coalesce(completed_at,?) where id=? and status in ('LEASED','RUNNING')").params(Timestamp.from(now),trialId).update();
                return new HeartbeatReply(true,row.context().leaseExpiresAt());
            }
            requireLive(row,now);
            if(row.context().status()!=TrialExecutionStatus.LEASED && row.context().status()!=TrialExecutionStatus.RUNNING) throw new AttemptRejectedException();
            Instant expires=min(now.plusSeconds(60),row.context().deadline());
            jdbc.sql("update trial_leases set expires_at=? where trial_id=?").params(Timestamp.from(expires),trialId).update();
            jdbc.sql("update worker_instances set last_seen_at=? where id=?").params(Timestamp.from(now),row.context().workerId()).update();
            return new HeartbeatReply(false,expires);
        });
    }
    @Override public void complete(WorkerPrincipal p,UUID trialId,String token,CompletionRequest request,Instant now) {
        tx.executeWithoutResult(status -> {
            var row=lockAttempt(p,trialId,token); var payload=json.valueToTree(request); String hash=CanonicalJson.hash(payload);
            if(row.completionHash()!=null) { if(!row.completionHash().equals(hash)) throw new AttemptRejectedException(); return; }
            for(var artifactId:request.artifactIds()) {
                if(jdbc.sql("select id from artifacts where id=? and workspace_id=? and trial_id=?").params(artifactId,p.workspaceId(),trialId).query(UUID.class).optional().isEmpty()) throw new AttemptRejectedException();
            }
            TrialExecutionStatus terminal=request.status();
            if(row.context().experimentStatus().equals("CANCELLED") || row.context().status()==TrialExecutionStatus.CANCELLED) terminal=TrialExecutionStatus.CANCELLED;
            else {
                requireLive(row,now);
                if(row.context().status()!=TrialExecutionStatus.RUNNING) throw new AttemptRejectedException();
                if(!row.context().deadline().isAfter(now)) terminal=TrialExecutionStatus.TIMED_OUT;
            }
            jdbc.sql("update trials set status=?,completion=cast(? as jsonb),completion_hash=?,completed_at=coalesce(completed_at,?) where id=?")
                .params(terminal.name(),payload.toString(),hash,Timestamp.from(now),trialId).update();
        });
    }
    private void expireLost(Instant now) {
        LeaseSweeper.expire(jdbc,now,1000);
    }
    private int activeCount(UUID workspace,UUID worker) {
        String query="select count(*) from trials t join trial_leases l on l.trial_id=t.id where t.status in ('LEASED','RUNNING')";
        if(workspace!=null) return jdbc.sql(query+" and t.workspace_id=?").param(workspace).query(Integer.class).single();
        if(worker!=null) return jdbc.sql(query+" and l.worker_id=?").param(worker).query(Integer.class).single();
        return jdbc.sql(query).query(Integer.class).single();
    }
    private AttemptRow lockAttempt(WorkerPrincipal p,UUID id,String token) {
        // Match cancellation/claim lock order before touching experiment or trial rows.
        jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single();
        requireCredential(p);
        var row=jdbc.sql("select t.status,t.completion_hash,t.completed_at,l.worker_id,l.token_hash,l.expires_at,l.execution_deadline,e.status experiment_status from trial_leases l join trials t on t.id=l.trial_id join experiments e on e.id=t.experiment_id join worker_instances w on w.id=l.worker_id where l.trial_id=? and l.workspace_id=? and w.credential_id=? for update of t,l,e")
            .params(id,p.workspaceId(),p.credentialId()).query((r,n) -> new AttemptRow(new AttemptContext(p.workspaceId(),id,r.getObject("worker_id",UUID.class),TrialExecutionStatus.valueOf(r.getString("status")),r.getTimestamp("expires_at").toInstant(),r.getTimestamp("execution_deadline").toInstant(),r.getString("experiment_status")),r.getString("token_hash"),r.getString("completion_hash"),r.getTimestamp("completed_at")==null?null:r.getTimestamp("completed_at").toInstant())).optional().orElseThrow(AttemptRejectedException::new);
        if(!TokenSecrets.matches(row.tokenHash(),token)) throw new AttemptRejectedException(); return row;
    }
    private void requireCredential(WorkerPrincipal p) {
        if(jdbc.sql("select id from worker_credentials where id=? and workspace_id=? and revoked=false for share").params(p.credentialId(),p.workspaceId()).query(UUID.class).optional().isEmpty()) throw new InvalidWorkerCredentialException();
    }
    private void requireLive(AttemptRow row,Instant now) {
        if(!row.context().leaseExpiresAt().isAfter(now) || row.context().status()==TrialExecutionStatus.EXECUTION_UNKNOWN || row.context().status()==TrialExecutionStatus.TIMED_OUT) throw new AttemptRejectedException();
    }
    private JsonNode tree(String value) { try { return json.readTree(value); } catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); } }
    private static Instant min(Instant a,Instant b) { return a.isBefore(b)?a:b; }
    private record Candidate(UUID id,String traceId,JsonNode task,Instant deadline) {}
    private record AttemptRow(AttemptContext context,String tokenHash,String completionHash,Instant completedAt) {}
}
