package io.agenteval.evaluation;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Repository
public class JdbcEvaluatorPreviewRepository implements EvaluatorPreviewRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    public JdbcEvaluatorPreviewRepository(JdbcClient jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc; transactions=new TransactionTemplate(manager); }
    public UUID create(WorkspaceActor actor,UUID draft,long revision,UUID trial,String key,Supplier<UUID> run,Instant now) {
        String hash=CanonicalJson.hash(JsonNodeFactory.instance.objectNode().put("draft",draft.toString()).put("revision",revision).put("trial",trial.toString()));
        return transactions.execute(status -> {
            jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single();
            var existing=jdbc.sql("select run_id,request_hash from evaluator_previews where workspace_id=? and trial_id=? and idempotency_key=?").params(actor.workspaceId(),trial,key).query((row,index)->Map.entry(row.getObject("run_id",UUID.class),row.getString("request_hash"))).optional();
            if(existing.isPresent()) { if(!existing.get().getValue().equals(hash)) throw new CatalogConflictException(); return existing.get().getKey(); }
            UUID id=run.get();
            if(jdbc.sql("update grading_jobs set purpose='PREVIEW' where id=? and workspace_id=? and trial_id=? and not initial_run").params(id,actor.workspaceId(),trial).update()!=1) throw new ScopeViolationException();
            // A preview can be the first consumer to freeze terminal evidence. Preserve normal suite grading too.
            jdbc.sql("""
                insert into grading_jobs(id,workspace_id,trial_id,snapshot_id,suite_version_id,next_run_at,created_at)
                select ?,t.workspace_id,t.id,s.id,e.suite_version_id,?,? from trials t join experiments e on e.id=t.experiment_id
                  join evidence_snapshots s on s.id=(select first.id from evidence_snapshots first where first.trial_id=t.id order by first.ordinal limit 1)
                where t.id=? and t.workspace_id=? and not exists(select 1 from grading_jobs g where g.trial_id=t.id and g.purpose<>'PREVIEW')
                """).params(UUID.randomUUID(),Timestamp.from(now),Timestamp.from(now),trial,actor.workspaceId()).update();
            jdbc.sql("insert into evaluator_previews(run_id,workspace_id,trial_id,draft_id,draft_revision,idempotency_key,request_hash,created_at) values(?,?,?,?,?,?,?,?)").params(id,actor.workspaceId(),trial,draft,revision,key,hash,Timestamp.from(now)).update();
            jdbc.sql("""
                insert into ephemeral_catalog_resources(resource_id,workspace_id,resource_kind,origin_experiment_id)
                select r.id,r.workspace_id,r.kind,t.experiment_id from grading_jobs g join trials t on t.id=g.trial_id
                join catalog_versions v on v.workspace_id=g.workspace_id and (v.id=g.suite_version_id or exists(select 1 from jsonb_array_elements(g.run_config->'evaluators') item where item->>'id'=v.id::text))
                join catalog_resources r on r.id=v.resource_id and r.archived where g.id=? on conflict(resource_id) do nothing
                """
            ).param(id).update();
            return id;
        });
    }
}
