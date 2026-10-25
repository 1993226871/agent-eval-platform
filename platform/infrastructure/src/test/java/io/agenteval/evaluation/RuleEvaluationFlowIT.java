package io.agenteval.evaluation;
import io.agenteval.agents.*;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import io.agenteval.experiments.*;
import io.agenteval.telemetry.*;
import io.agenteval.reporting.*;
import io.agenteval.protocol.*;
import com.fasterxml.jackson.databind.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class RuleEvaluationFlowIT {
    static PostgreSQLContainer<?> postgres;
    static JdbcTemplate sql;
    static JdbcClient jdbc;
    static DataSourceTransactionManager tx;
    static ObjectMapper json=new ObjectMapper();
    final Instant now=Instant.parse("2026-10-26T00:00:00Z");
    WorkspaceActor actor;
    CatalogService catalog;
    AgentService agents;
    ExperimentService experiments;
    EvidenceService evidence;
    JdbcEvaluationRepository repository;
    EvaluationOrchestrator orchestrator;
    ReportService reports;
    @BeforeAll static void database() {
        postgres=new PostgreSQLContainer<>("postgres:17.11"); postgres.start();
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate(); jdbc=JdbcClient.create(ds); sql=new JdbcTemplate(ds); tx=new DataSourceTransactionManager(ds);
    }
    @AfterAll static void stop() { if(postgres!=null) postgres.stop(); }
    @BeforeEach void setup() {
        actor=new IdentityService(new JdbcIdentityRepository(jdbc,tx),new BCryptPasswordEncoder(4)).createUser(UUID.randomUUID()+"@test.local","Temporary-Password-1",false).actor();
        catalog=new CatalogService(new JdbcCatalogRepository(jdbc,tx,json)); agents=new AgentService(new JdbcAgentRepository(jdbc));
        experiments=new ExperimentService(new JdbcExperimentRepository(jdbc,tx,json),catalog,agents,Clock.fixed(now,ZoneOffset.UTC));
        evidence=new EvidenceService(new JdbcEvidenceRepository(jdbc,tx,json),Clock.fixed(now,ZoneOffset.UTC)); restart(now);
    }
    void restart(Instant time) { repository=new JdbcEvaluationRepository(jdbc,tx,json); orchestrator=new EvaluationOrchestrator(repository,evidence,new RuleEvaluator(),Clock.fixed(time,ZoneOffset.UTC)); reports=new ReportService(repository,new EvaluationAggregator()); }
    record Fixture(UUID experiment,List<UUID> snapshots) {}
    Fixture experiment(List<String> outputs,boolean optional) throws Exception {
        return experiment(outputs,optional,"RULE");
    }
    Fixture experiment(List<String> outputs,boolean optional,String type) throws Exception {
        var agent=agents.create(actor,"orders","v1"); var cases=json.createObjectNode(); var tasks=cases.putArray("cases");
        for(int i=0;i<outputs.size();i++) { var task=tasks.addObject().put("instruction","check amount"); task.putObject("input").put("order_id","A"); task.putObject("expected").put("amount",100); }
        var dataset=catalog.create(actor,ResourceKind.DATASET,"orders",cases);
        var hardContent=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"amount\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{\"operator\":\"eq\",\"selector\":\"/output/amount\",\"expected\":100}}");
        hardContent.put("type",type);
        if(type.equals("LLM")) hardContent.putObject("config").put("rubric","Score whether the supplied answer is supported").put("scale_max",4);
        if(type.equals("PYTHON")) hardContent.putObject("config").put("code","def evaluate(context):\n    return {'status': 'SCORED', 'score': 1}\n");
        var hard=catalog.create(actor,ResourceKind.EVALUATOR,"amount",hardContent);
        var suiteContent=json.createObjectNode(); var entries=suiteContent.putArray("entries");
        entries.addObject().put("evaluator_version",catalog.publish(actor,hard.id()).id().toString()).put("dimension","outcome").put("required_to_pass",true).put("weight",1);
        if(optional) {
            var extra=catalog.create(actor,ResourceKind.EVALUATOR,"optional",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"optional\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{\"operator\":\"schema\",\"selector\":\"/output\",\"schema\":{\"type\":\"object\"}}}"));
            entries.addObject().put("evaluator_version",catalog.publish(actor,extra.id()).id().toString()).put("dimension","outcome").put("required_to_pass",false).put("weight",9);
        }
        var suite=catalog.create(actor,ResourceKind.SUITE,"suite",suiteContent);
        UUID id=experiments.start(actor,new StartExperimentCommand(agent.id(),"v1",catalog.publish(actor,dataset.id()).id(),catalog.publish(actor,suite.id()).id(),1,now.plusSeconds(3600),UUID.randomUUID().toString()));
        var snapshots=new ArrayList<UUID>();
        for(var trial:experiments.trials(actor,id)) {
            String output=outputs.get(trial.caseIndex());
            sql.update("update trials set status='SUCCEEDED',started_at=?,completed_at=?,completion=cast(? as jsonb) where id=?",java.sql.Timestamp.from(now.minusSeconds(60)),java.sql.Timestamp.from(now.minusSeconds(31)),"{\"status\":\"SUCCEEDED\",\"output\":"+output+",\"final_sequence\":0,\"event_count\":0,\"dropped_count\":0,\"artifact_ids\":[]}",trial.id());
            snapshots.add(evidence.freeze(actor,trial.id(),false).id());
        }
        return new Fixture(id,List.copyOf(snapshots));
    }
    void drain() { orchestrator.schedule(200); for(int i=0;i<300 && orchestrator.runNext();i++) {} }
    @Test void restartsWithoutDuplicateScoresAndExplainsDenominators() throws Exception {
        var fixture=experiment(List.of("{\"amount\":100}","{\"amount\":80}","{}"),false); drain(); restart(now); drain();
        assertThat(sql.queryForObject("select count(*) from evaluation_results where workspace_id=?",Integer.class,actor.workspaceId())).isEqualTo(3);
        var report=reports.get(actor,fixture.experiment());
        assertThat(report.plannedAttempts()).isEqualTo(3); assertThat(report.startedAttempts()).isEqualTo(3); assertThat(report.determinableAttempts()).isEqualTo(2); assertThat(report.passedAttempts()).isEqualTo(1);
        assertThat(report.passRatePlanned().denominator()).isEqualTo(3); assertThat(report.passRateDeterminable().denominator()).isEqualTo(2); assertThat(report.passRateDeterminable().value()).isEqualByComparingTo("0.5");
        var page=reports.trials(actor,fixture.experiment(),null,2); assertThat(page.items()).hasSize(2); assertThat(page.nextCursor()).isNotNull(); assertThat(reports.trials(actor,fixture.experiment(),page.nextCursor(),2).items()).hasSize(1);
    }
    @Test void optionalHighScoresCannotCompensateForRequiredFailure() throws Exception {
        var fixture=experiment(List.of("{\"amount\":80}"),true); drain();
        var page=reports.trials(actor,fixture.experiment(),null,100); assertThat(page.items().getFirst().verdict()).isEqualTo("FAIL");
        assertThat(reports.get(actor,fixture.experiment()).dimensions().get("outcome").score()).isEqualByComparingTo("0.9");
    }
    @Test void expiredProcessingLeaseFencesOldCompletionAndKeepsOneResult() throws Exception {
        var fixture=experiment(List.of("{\"amount\":100}"),false); orchestrator.schedule(200);
        var old=repository.claim(now).orElseThrow(); restart(now.plusSeconds(61)); var current=repository.claim(now.plusSeconds(61)).orElseThrow();
        assertThat(current.id()).isEqualTo(old.id());
        var result=new RuleEvaluator().evaluate(current.definition(),evidence.context(actor,current.snapshotId()));
        assertThat(repository.complete(old,result,now.plusSeconds(62))).isFalse();
        assertThat(repository.complete(current,result,now.plusSeconds(62))).isTrue();
        assertThat(repository.complete(current,result,now.plusSeconds(62))).isTrue();
        assertThat(sql.queryForObject("select count(*) from evaluation_results where workspace_id=?",Integer.class,actor.workspaceId())).isEqualTo(1);
    }
    @Test void cancellationAndForeignWorkspaceDoNotCreateOrExposeScores() throws Exception {
        var fixture=experiment(List.of("{\"amount\":100}"),false); orchestrator.schedule(200); experiments.cancel(actor,fixture.experiment()); drain();
        assertThat(sql.queryForObject("select count(*) from evaluation_results where workspace_id=?",Integer.class,actor.workspaceId())).isZero();
        var other=new WorkspaceActor(UUID.randomUUID(),UUID.randomUUID(),false);
        assertThatThrownBy(() -> reports.get(other,fixture.experiment())).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(() -> reports.trials(other,fixture.experiment(),null,100)).isInstanceOf(ScopeViolationException.class);
    }
    @Test void rejectsSuiteWithoutRequiredCriterionBeforeExperimentStart() throws Exception {
        var evaluator=catalog.create(actor,ResourceKind.EVALUATOR,"rule",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"optional\",\"required_evidence\":[],\"threshold\":1,\"config\":{}}"));
        var version=catalog.publish(actor,evaluator.id());
        var suite=catalog.create(actor,ResourceKind.SUITE,"invalid",json.readTree("{\"entries\":[{\"evaluator_version\":\""+version.id()+"\",\"dimension\":\"outcome\",\"required_to_pass\":false,\"weight\":1}]}"));
        assertThatThrownBy(() -> catalog.publish(actor,suite.id())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void efficiencyDoesNotReadLateProjectionOutsideFrozenSnapshot() throws Exception {
        var fixture=experiment(List.of("{\"amount\":100}"),false); drain();
        var trial=experiments.trials(actor,fixture.experiment()).getFirst();
        sql.update("insert into spans(workspace_id,trial_id,span_id,name,kind,status,duration_ms) values (?,?,?,?,?,'COMPLETED',12)",actor.workspaceId(),trial.id(),"1".repeat(16),"agent.run","agent");
        assertThat(reports.get(actor,fixture.experiment()).efficiency().observedDurations()).isZero();
        var codec=new ProtocolCodec();
        for(int sequence=1;sequence<=2;sequence++) {
            var event=new TraceEvent("1.0",UUID.randomUUID(),trial.id(),trial.traceId(),"1".repeat(16),null,sequence==1?EventType.SPAN_STARTED:EventType.SPAN_COMPLETED,SpanKind.AGENT,"agent.run",sequence,now.minusSeconds(60-sequence),codec.tree(sequence==1?"{\"input\":{}}":"{\"output\":{\"amount\":100},\"duration_ms\":12}"),new CaptureMetadata(false,false));
            var node=codec.tree(codec.encode(event));
            sql.update("insert into trace_events(workspace_id,trial_id,event_id,sequence,event_json,content_hash,received_at) values (?,?,?,?,cast(? as jsonb),?,?)",actor.workspaceId(),trial.id(),event.eventId(),sequence,node.toString(),CanonicalJson.hash(node),java.sql.Timestamp.from(now));
        }
        var updated=evidence.freeze(actor,trial.id(),true); drain();
        assertThat(reports.trials(actor,fixture.experiment(),null,100).items().getFirst().snapshotId()).isEqualTo(updated.id());
        var efficiency=reports.get(actor,fixture.experiment()).efficiency(); assertThat(efficiency.observedDurations()).isEqualTo(1); assertThat(efficiency.p50DurationMs()).isEqualTo(12L);
    }
    @Test void disabledEvaluatorCapabilitiesRemainUnscoredAndUndetermined() throws Exception {
        for(String type:List.of("LLM","PYTHON")) {
            var fixture=experiment(List.of("{\"amount\":100}"),false,type); drain();
            var trial=reports.trials(actor,fixture.experiment(),null,100).items().getFirst();
            assertThat(trial.verdict()).isEqualTo("UNDETERMINED"); assertThat(trial.evaluationState()).isEqualTo("PARTIAL");
            assertThat(trial.criteria().getFirst().status()).isEqualTo(io.agenteval.protocol.CriterionStatus.SKIPPED); assertThat(trial.criteria().getFirst().score()).isNull();
            assertThat(reports.get(actor,fixture.experiment()).dimensions().get("outcome").score()).isNull();
        }
    }
}
