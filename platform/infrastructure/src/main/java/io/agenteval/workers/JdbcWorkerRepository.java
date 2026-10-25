package io.agenteval.workers;
import io.agenteval.catalog.CatalogConflictException;
import io.agenteval.identity.*;
import io.agenteval.protocol.RegisterWorkerRequest;
import io.agenteval.protocol.WorkerRegistration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

@Repository
public class JdbcWorkerRepository implements CredentialRepository,WorkerRegistry.Repository {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    public JdbcWorkerRepository(JdbcClient jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc; tx=new TransactionTemplate(manager); }
    @Override public void issue(WorkspaceActor actor,UUID id,String hash,Set<UUID> agentIds) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("insert into worker_credentials(id,workspace_id,secret_hash) values (?,?,?)").params(id,actor.workspaceId(),hash).update();
            for(var agentId:agentIds) jdbc.sql("insert into worker_credential_agents(credential_id,workspace_id,agent_id) values (?,?,?)").params(id,actor.workspaceId(),agentId).update();
        });
    }
    @Override public WorkerPrincipal authenticate(String hash) {
        var credential=jdbc.sql("select id,workspace_id from worker_credentials where secret_hash=? and revoked=false").param(hash)
            .query((row,n) -> new WorkerPrincipal(row.getObject("workspace_id",UUID.class),Set.of(),row.getObject("id",UUID.class))).optional().orElseThrow(InvalidWorkerCredentialException::new);
        var agentIds=new HashSet<>(jdbc.sql("select agent_id from worker_credential_agents where credential_id=? and workspace_id=?").params(credential.credentialId(),credential.workspaceId()).query(UUID.class).list());
        return new WorkerPrincipal(credential.workspaceId(),agentIds,credential.credentialId());
    }
    @Override public void revoke(WorkspaceActor actor,UUID id) {
        if(jdbc.sql("update worker_credentials set revoked=true where id=? and workspace_id=?").params(id,actor.workspaceId()).update()==0) throw new ScopeViolationException();
    }
    @Override public WorkerRegistration register(WorkerPrincipal p,RegisterWorkerRequest request,Instant now) {
        return tx.execute(status -> {
            jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single();
            requireCredential(p);
            if(!jdbc.sql("select exists(select 1 from worker_credential_agents where credential_id=? and workspace_id=? and agent_id=?)")
                .params(p.credentialId(),p.workspaceId(),request.agentId()).query(Boolean.class).single()) throw new ScopeViolationException();
            var existing=jdbc.sql("select id,agent_id,agent_version,max_concurrency from worker_instances where credential_id=? and instance_id=? for update")
                .params(p.credentialId(),request.instanceId()).query((row,n) -> new Existing(row.getObject("id",UUID.class),row.getObject("agent_id",UUID.class),row.getString("agent_version"),row.getInt("max_concurrency"))).optional();
            UUID id=existing.map(Existing::id).orElseGet(UUID::randomUUID);
            if(existing.isPresent()) {
                var old=existing.get();
                boolean changed=!old.agentId().equals(request.agentId()) || !old.version().equals(request.agentVersion()) || old.concurrency()!=request.maxConcurrency();
                int active=jdbc.sql("select count(*) from trial_leases l join trials t on t.id=l.trial_id where l.worker_id=? and t.status in ('LEASED','RUNNING') and l.expires_at>?")
                    .params(id,Timestamp.from(now)).query(Integer.class).single();
                if(changed && active>0) throw new CatalogConflictException();
                jdbc.sql("update worker_instances set agent_id=?,agent_version=?,protocol_version=?,sdk_language=?,sdk_version=?,max_concurrency=?,last_seen_at=? where id=?")
                    .params(request.agentId(),request.agentVersion(),request.protocolVersion(),request.sdkLanguage(),request.sdkVersion(),request.maxConcurrency(),Timestamp.from(now),id).update();
            } else {
                int online=jdbc.sql("select count(*) from worker_instances w join worker_credentials c on c.id=w.credential_id where c.revoked=false and w.last_seen_at>?").param(Timestamp.from(now.minusSeconds(60))).query(Integer.class).single();
                if(online>=50) throw new CapacityExceededException();
                jdbc.sql("insert into worker_instances(id,workspace_id,credential_id,agent_id,agent_version,instance_id,protocol_version,sdk_language,sdk_version,max_concurrency,last_seen_at) values (?,?,?,?,?,?,?,?,?,?,?)")
                    .params(id,p.workspaceId(),p.credentialId(),request.agentId(),request.agentVersion(),request.instanceId(),request.protocolVersion(),request.sdkLanguage(),request.sdkVersion(),request.maxConcurrency(),Timestamp.from(now)).update();
            }
            return new WorkerRegistration(id,15,60);
        });
    }
    private void requireCredential(WorkerPrincipal p) {
        if(jdbc.sql("select id from worker_credentials where id=? and workspace_id=? and revoked=false for share").params(p.credentialId(),p.workspaceId()).query(UUID.class).optional().isEmpty()) throw new InvalidWorkerCredentialException();
    }
    private record Existing(UUID id,UUID agentId,String version,int concurrency) {}
}
