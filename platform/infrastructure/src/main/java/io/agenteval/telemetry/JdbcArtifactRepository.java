package io.agenteval.telemetry;
import io.agenteval.experiments.*;
import io.agenteval.catalog.CatalogConflictException;
import io.agenteval.identity.*;
import io.agenteval.protocol.RegisterArtifactRequest;
import io.agenteval.workers.WorkerPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

@Repository
public class JdbcArtifactRepository implements ArtifactRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final LeaseRepository leases;
    public JdbcArtifactRepository(JdbcClient jdbc,PlatformTransactionManager manager,LeaseRepository leases) { this.jdbc=jdbc; tx=new TransactionTemplate(manager); this.leases=leases; }
    @Override public ArtifactRecord reserve(WorkerPrincipal p,UUID trial,String token,RegisterArtifactRequest request,Instant now) {
        return tx.execute(transaction -> {
            leases.validateUploadAttempt(p,trial,token,now);
            // Explicit Trial row lock also protects aggregate reservations for alternate LeaseRepository implementations.
            jdbc.sql("select id from trials where id=? and workspace_id=? for update").params(trial,p.workspaceId()).query(UUID.class).optional().orElseThrow(AttemptRejectedException::new);
            var old=jdbc.sql("select * from artifacts where trial_id=? and idempotency_key=?").params(trial,request.idempotencyKey()).query(this::map).optional();
            if(old.isPresent()) {
                var a=old.get(); if(!a.name().equals(request.name()) || !a.mediaType().equals(request.mediaType()) || a.sizeBytes()!=request.sizeBytes() || !a.sha256().equals(request.sha256())) throw new CatalogConflictException(); return a;
            }
            long used=jdbc.sql("select coalesce(sum(size_bytes),0) from artifacts where trial_id=?").param(trial).query(Long.class).single();
            if(used+request.sizeBytes()>104857600) throw new TelemetryLimitException();
            UUID id=UUID.randomUUID(); String stage="staging/"+p.workspaceId()+"/"+id,sealed="sealed/"+p.workspaceId()+"/"+id;
            jdbc.sql("insert into artifacts(id,workspace_id,trial_id,name,media_type,size_bytes,sha256,staging_key,object_key,idempotency_key,created_at) values (?,?,?,?,?,?,?,?,?,?,?)")
                .params(id,p.workspaceId(),trial,request.name(),request.mediaType(),request.sizeBytes(),request.sha256(),stage,sealed,request.idempotencyKey(),Timestamp.from(now)).update();
            return find(p.workspaceId(),trial,id);
        });
    }
    @Override public ArtifactRecord beginVerification(WorkerPrincipal p,UUID trial,String token,UUID id,Instant now) {
        return tx.execute(transaction -> {
            leases.validateUploadAttempt(p,trial,token,now); var artifact=find(p.workspaceId(),trial,id);
            if(artifact.status().equals("AVAILABLE")) return artifact;
            UUID verification=UUID.randomUUID();
            int updated=jdbc.sql("update artifacts set status='VERIFYING',verification_token=?,verification_started_at=? where id=? and (status='PENDING' or (status='VERIFYING' and verification_started_at<?))")
                .params(verification,Timestamp.from(now),id,Timestamp.from(now.minusSeconds(120))).update();
            if(updated!=1) throw new EvidencePendingException(); return find(p.workspaceId(),trial,id);
        });
    }
    @Override public void finishVerification(WorkerPrincipal p,UUID trial,String token,ArtifactRecord artifact,Instant now) {
        tx.executeWithoutResult(transaction -> {
            leases.validateUploadAttempt(p,trial,token,now);
            if(jdbc.sql("update artifacts set status='AVAILABLE',verified_at=? where id=? and workspace_id=? and trial_id=? and status='VERIFYING' and verification_token=?")
                .params(Timestamp.from(now),artifact.id(),p.workspaceId(),trial,artifact.verificationToken()).update()!=1) throw new EvidencePendingException();
        });
    }
    @Override public void abandonVerification(ArtifactRecord artifact) { jdbc.sql("update artifacts set status='PENDING',verification_token=null,verification_started_at=null where id=? and status='VERIFYING' and verification_token=?").params(artifact.id(),artifact.verificationToken()).update(); }
    @Override public ArtifactRecord get(WorkspaceActor actor,UUID id) { return jdbc.sql("select * from artifacts where id=? and workspace_id=?").params(id,actor.workspaceId()).query(this::map).optional().orElseThrow(ScopeViolationException::new); }
    @Override public ArtifactRecord getEvidence(WorkspaceActor actor,UUID trial,UUID snapshot,UUID id) {
        return jdbc.sql("""
            select a.* from artifacts a join evidence_snapshots s on s.trial_id=a.trial_id and s.workspace_id=a.workspace_id
            where a.id=? and a.workspace_id=? and a.trial_id=? and s.id=? and a.status='AVAILABLE'
              and exists(select 1 from jsonb_array_elements(s.manifest->'artifacts') v where v->>'artifact_id'=a.id::text
                and v->>'sha256'=a.sha256 and (v->>'size_bytes')::bigint=a.size_bytes and v->>'name'=a.name and v->>'media_type'=a.media_type)
            """).params(id,actor.workspaceId(),trial,snapshot).query(this::map).optional().orElseThrow(ScopeViolationException::new);
    }
    private ArtifactRecord find(UUID workspace,UUID trial,UUID id) { return jdbc.sql("select * from artifacts where id=? and workspace_id=? and trial_id=? for update").params(id,workspace,trial).query(this::map).optional().orElseThrow(AttemptRejectedException::new); }
    private ArtifactRecord map(java.sql.ResultSet r,int n) throws java.sql.SQLException { return new ArtifactRecord(r.getObject("id",UUID.class),r.getObject("trial_id",UUID.class),r.getObject("workspace_id",UUID.class),r.getString("name"),r.getString("media_type"),r.getLong("size_bytes"),r.getString("sha256"),r.getString("staging_key"),r.getString("object_key"),r.getString("status"),r.getObject("verification_token",UUID.class)); }
}
