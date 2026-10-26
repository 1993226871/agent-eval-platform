package io.agenteval.reporting;

import com.fasterxml.jackson.databind.*;
import io.agenteval.evaluation.HumanReview;
import io.agenteval.identity.WorkspaceActor;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcReportQueries implements ComparisonQueries {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ReportRepository reports;
    private final TransactionTemplate read;
    public JdbcReportQueries(JdbcClient jdbc, PlatformTransactionManager manager, ObjectMapper json, io.agenteval.evaluation.JdbcEvaluationRepository reports) {
        this.jdbc=jdbc; this.json=json; this.reports=reports; read=new TransactionTemplate(manager); read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public ReportData load(WorkspaceActor actor, UUID experiment) { return reports.load(actor,experiment); }
    @Override public Pair pair(WorkspaceActor actor, UUID left, UUID right) { return read.execute(status -> new Pair(input(actor,left),input(actor,right))); }
    private Input input(WorkspaceActor actor, UUID experiment) {
        var data=reports.load(actor,experiment);
        var versions=new TreeSet<String>(); var bindings=new TreeSet<String>();
        // Project only immutable identifiers; never load evaluator code or dataset bodies for comparison.
        var configurations=jdbc.sql("""
            select distinct v->>'id' evaluator, v#>>'{content,config,model_profile_id}' model, v#>>'{content,config,price_version_id}' price
            from experiments e join trials t on t.experiment_id=e.id
            left join lateral (select g.run_config from grading_jobs g where g.trial_id=t.id and g.purpose<>'PREVIEW' order by g.run_ordinal desc limit 1) latest on true
            cross join lateral jsonb_array_elements(coalesce(latest.run_config,e.snapshot)->'evaluators') v
            where e.id=? and e.workspace_id=?
            """
        ).params(experiment,actor.workspaceId()).query((row,n) -> new String[]{row.getString("evaluator"),row.getString("model"),row.getString("price")}).list();
        for(var fields:configurations) { versions.add(fields[0]); if(fields[1]!=null || fields[2]!=null) bindings.add(Objects.toString(fields[1],"missing")+"/"+Objects.toString(fields[2],"missing")); }
        var scopes=jdbc.sql("""
            select t.id,t.case_index,t.attempt,s.id snapshot_id,s.content_hash,s.completeness,
              case when s.id is null then 'PENDING' when s.ordinal=(select min(first.ordinal) from evidence_snapshots first where first.trial_id=t.id) then 'ORIGINAL' else 'REVISED' end scope
            from trials t left join lateral (select g.snapshot_id from grading_jobs g where g.trial_id=t.id and g.purpose<>'PREVIEW' order by g.run_ordinal desc limit 1) latest on true
            left join evidence_snapshots s on s.id=latest.snapshot_id
            where t.experiment_id=? and t.workspace_id=? order by t.case_index,t.attempt
            """
        ).params(experiment,actor.workspaceId()).query((row,n) -> new ReportProvenance.Evidence(row.getObject("id",UUID.class),row.getInt("case_index"),row.getInt("attempt"),row.getObject("snapshot_id",UUID.class),row.getString("content_hash"),row.getString("completeness"),row.getString("scope"))).list();
        return new Input(data,new ReportProvenance(List.copyOf(versions),List.copyOf(bindings),scopes));
    }
    @Override public ExportInput export(WorkspaceActor actor, UUID experiment,long byteLimit) {
        return read.execute(status -> {
            jdbc.sql("select id from experiments where id=? and workspace_id=? and status not in ('DELETING','DELETED')").params(experiment,actor.workspaceId()).query(UUID.class).optional().orElseThrow(io.agenteval.identity.ScopeViolationException::new);
            long bytes=jdbc.sql("""
                with selected_trials as (select id from trials where experiment_id=? and workspace_id=?),
                latest as (select distinct on(g.trial_id) g.id,g.snapshot_id from grading_jobs g join selected_trials t on t.id=g.trial_id where g.purpose<>'PREVIEW' order by g.trial_id,g.run_ordinal desc)
                select 4096+(select count(*)*2048 from selected_trials)
                    +coalesce((select sum(octet_length((s.manifest-'experiment_snapshot')::text)) from evidence_snapshots s join latest l on l.snapshot_id=s.id),0)
                    +coalesce((select sum(octet_length(r.payload::text)+512) from evaluation_results r join latest l on l.id=r.run_id),0)
                    +coalesce((select sum(octet_length(h.reason)*6+512) from human_reviews h join evaluation_results r on r.job_id=h.result_id join latest l on l.id=r.run_id),0)
                """
            ).params(experiment,actor.workspaceId()).query(Long.class).single();
            if(bytes>byteLimit) throw new ReportExportLimitException();
            var input=input(actor,experiment);
            var reviews=jdbc.sql("""
                with latest as (select distinct on(g.trial_id) g.id from grading_jobs g join trials t on t.id=g.trial_id where t.experiment_id=? and t.workspace_id=? and g.purpose<>'PREVIEW' order by g.trial_id,g.run_ordinal desc)
                select h.* from human_reviews h join evaluation_results r on r.job_id=h.result_id join latest l on l.id=r.run_id where h.workspace_id=? order by h.created_at,h.id
                """
            ).params(experiment,actor.workspaceId(),actor.workspaceId()).query((row,n) -> new HumanReview(row.getObject("id",UUID.class),row.getObject("result_id",UUID.class),row.getBigDecimal("score"),row.getString("reason"),row.getObject("reviewer_id",UUID.class),row.getTimestamp("created_at").toInstant())).list();
            var frozen=jdbc.sql("""
                with latest as (select distinct on(g.trial_id) g.trial_id,g.snapshot_id from grading_jobs g join trials t on t.id=g.trial_id where t.experiment_id=? and t.workspace_id=? and g.purpose<>'PREVIEW' order by g.trial_id,g.run_ordinal desc)
                select l.trial_id,s.id,s.content_hash,s.completeness,(s.manifest-'experiment_snapshot')::text manifest
                from latest l join evidence_snapshots s on s.id=l.snapshot_id where s.workspace_id=? order by l.trial_id
                """
            ).params(experiment,actor.workspaceId(),actor.workspaceId()).query((row,n) -> new FrozenEvidence(row.getObject("trial_id",UUID.class),row.getObject("id",UUID.class),row.getString("content_hash"),row.getString("completeness"),tree(row.getString("manifest")))).list();
            var identities=jdbc.sql("""
                with latest as (select distinct on(g.trial_id) g.id from grading_jobs g join trials t on t.id=g.trial_id where t.experiment_id=? and t.workspace_id=? and g.purpose<>'PREVIEW' order by g.trial_id,g.run_ordinal desc)
                select r.job_id,r.run_id,r.trial_id,r.evaluator_version from evaluation_results r join latest l on l.id=r.run_id where r.workspace_id=? order by r.run_id,r.evaluator_version
                """
            ).params(experiment,actor.workspaceId(),actor.workspaceId()).query((row,n) -> new ResultIdentity(row.getObject("job_id",UUID.class),row.getObject("run_id",UUID.class),row.getObject("trial_id",UUID.class),row.getObject("evaluator_version",UUID.class))).list();
            return new ExportInput(input,reviews,frozen,identities);
        });
    }
    private JsonNode tree(String value) { try { return json.readTree(value); } catch(java.io.IOException invalid) { throw new IllegalStateException("Invalid report evidence",invalid); } }
}
