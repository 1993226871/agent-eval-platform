package io.agenteval.experiments;
import io.agenteval.catalog.CatalogConflictException;
import io.agenteval.identity.*;
import io.agenteval.protocol.TrialExecutionStatus;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Repository
public class JdbcExperimentRepository implements ExperimentRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public JdbcExperimentRepository(JdbcClient jdbc,PlatformTransactionManager manager,ObjectMapper json) { this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.json=json; }
    @Override public Optional<UUID> replay(WorkspaceActor actor,String key,String hash) {
        return jdbc.sql("select id,request_hash from experiments where workspace_id=? and idempotency_key=?").params(actor.workspaceId(),key).query((row,n) -> {
            if(!hash.equals(row.getString("request_hash"))) throw new CatalogConflictException(); return row.getObject("id",UUID.class);
        }).optional();
    }
    @Override public UUID start(WorkspaceActor actor,StartExperimentCommand command,String hash,JsonNode snapshot,java.time.Instant now) {
        return tx.execute(status -> {
            // Serialize starts within a workspace; prevents duplicate-key races and partial trial expansion.
            jdbc.sql("select id from workspaces where id=? for update").param(actor.workspaceId()).query(UUID.class).single();
            var existing=replay(actor,command.idempotencyKey(),hash); if(existing.isPresent()) return existing.get();
            io.agenteval.administration.JdbcResourceAdmission.startExperiment(jdbc,actor.workspaceId(),Math.multiplyExact(snapshot.path("dataset").path("cases").size(),command.repeatCount()));
            for(UUID version:List.of(command.datasetVersionId(),command.suiteVersionId())) jdbc.sql("select id from catalog_versions where id=? and workspace_id=? for key share").params(version,actor.workspaceId()).query(UUID.class).optional().orElseThrow(ScopeViolationException::new);
            UUID id=UUID.randomUUID();
            jdbc.sql("insert into experiments(id,workspace_id,agent_id,agent_version,dataset_version_id,suite_version_id,repeat_count,deadline,snapshot,idempotency_key,request_hash,created_at) values (?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?)")
                .params(id,actor.workspaceId(),command.agentId(),command.agentVersion(),command.datasetVersionId(),command.suiteVersionId(),command.repeatCount(),java.sql.Timestamp.from(command.deadline()),snapshot.toString(),command.idempotencyKey(),hash,java.sql.Timestamp.from(now)).update();
            int index=0;
            for(var task:snapshot.path("dataset").path("cases")) {
                for(int attempt=1;attempt<=command.repeatCount();attempt++) {
                    jdbc.sql("insert into trials(id,experiment_id,workspace_id,case_index,attempt,trace_id,task,next_run_at,created_at) values (?,?,?,?,?,?,cast(? as jsonb),?,?)")
                        .params(UUID.randomUUID(),id,actor.workspaceId(),index,attempt,UUID.randomUUID().toString().replace("-",""),task.toString(),java.sql.Timestamp.from(now),java.sql.Timestamp.from(now)).update();
                }
                index++;
            }
            io.agenteval.administration.JdbcAuditWriter.append(jdbc,actor,io.agenteval.administration.AuditAction.EXPERIMENT_STARTED,id);
            return id;
        });
    }
    @Override public ExperimentRecord get(WorkspaceActor actor,UUID id) { return jdbc.sql("select * from experiments where id=? and workspace_id=? and status not in ('DELETING','DELETED')").params(id,actor.workspaceId()).query(this::map).optional().orElseThrow(ScopeViolationException::new); }
    @Override public List<ExperimentRecord> list(WorkspaceActor actor,int offset,int limit) { return jdbc.sql("select * from experiments where workspace_id=? and status not in ('DELETING','DELETED') order by created_at desc,id limit ? offset ?").params(actor.workspaceId(),limit,offset).query(this::map).list(); }
    @Override public List<TrialRecord> trials(WorkspaceActor actor,UUID experimentId) {
        return jdbc.sql("select * from trials where experiment_id=? and workspace_id=? order by case_index,attempt").params(experimentId,actor.workspaceId())
            .query((row,n) -> new TrialRecord(row.getObject("id",UUID.class),row.getObject("experiment_id",UUID.class),row.getObject("workspace_id",UUID.class),row.getInt("case_index"),row.getInt("attempt"),row.getString("trace_id"),TrialExecutionStatus.valueOf(row.getString("status")),tree(row.getString("task")))).list();
    }
    @Override public void cancel(WorkspaceActor actor,UUID id) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single();
            get(actor,id);
            int changed=jdbc.sql("update experiments set status='CANCELLED' where id=? and workspace_id=? and status<>'CANCELLED'").params(id,actor.workspaceId()).update();
            jdbc.sql("update trials set status='CANCELLED' where experiment_id=? and workspace_id=? and status='QUEUED'").params(id,actor.workspaceId()).update();
            if(changed==1) io.agenteval.administration.JdbcAuditWriter.append(jdbc,actor,io.agenteval.administration.AuditAction.EXPERIMENT_CANCELLED,id);
        });
    }
    private ExperimentRecord map(java.sql.ResultSet row,int n) throws java.sql.SQLException { return new ExperimentRecord(row.getObject("id",UUID.class),row.getObject("workspace_id",UUID.class),row.getObject("agent_id",UUID.class),row.getString("agent_version"),row.getObject("dataset_version_id",UUID.class),row.getObject("suite_version_id",UUID.class),row.getInt("repeat_count"),row.getTimestamp("deadline").toInstant(),row.getString("status"),tree(row.getString("snapshot"))); }
    private JsonNode tree(String value) { try { return json.readTree(value); } catch(java.io.IOException invalid) { throw new IllegalStateException("Corrupt experiment snapshot",invalid); } }
}
