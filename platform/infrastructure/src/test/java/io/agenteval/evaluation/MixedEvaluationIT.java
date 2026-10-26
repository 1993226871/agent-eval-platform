package io.agenteval.evaluation;
import io.agenteval.agents.*;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import io.agenteval.experiments.*;
import io.agenteval.telemetry.*;
import io.agenteval.model.*;
import io.agenteval.quota.*;
import io.agenteval.protocol.*;
import io.agenteval.reporting.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class MixedEvaluationIT {
    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static DataSourceTransactionManager tx;
    static ObjectMapper json=new ObjectMapper();
    final MutableClock clock=new MutableClock();
    final AtomicInteger ruleCount=new AtomicInteger(),pythonCount=new AtomicInteger(),providerCount=new AtomicInteger();
    WorkspaceActor owner,admin;
    CatalogService catalog;
    ExperimentService experiments;
    EvidenceService evidence;
    QuotaService quota;
    JdbcEvaluationRepository repository;
    EvaluationOrchestrator evaluations;
    ModelGateway gateway;
    OpenAiCompatibleJudgeClient provider;
    HttpServer server;
    UUID experiment,trial,suite,snapshot,ruleDraft,judgeDraft;
    @BeforeAll static void database() {
        postgres=new PostgreSQLContainer<>("postgres:17.11"); postgres.start();
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate(); jdbc=JdbcClient.create(ds); tx=new DataSourceTransactionManager(ds);
    }
    @AfterAll static void stopDatabase() { if(postgres!=null) postgres.stop(); }
    WorkspaceActor user(boolean admin) { return new IdentityService(new JdbcIdentityRepository(jdbc,tx),new BCryptPasswordEncoder(4)).createUser(UUID.randomUUID()+"@mixed.local","Temporary-Password-1",admin).actor(); }
    @BeforeEach void setup() throws Exception {
        owner=user(false); admin=user(true); quota=new QuotaService(new JdbcQuotaRepository(jdbc,tx));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange -> {
            providerCount.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            String content="{\"status\":\"SCORED\",\"grade\":4,\"reason\":\"Meets objective\",\"evidence_refs\":[\"task_instruction\",\"output\"]}";
            byte[] body=json.writeValueAsBytes(Map.of("id","mixed-call","model","fixture-v1","choices",List.of(Map.of("message",Map.of("content",content),"finish_reason","stop")),"usage",Map.of("prompt_tokens",10,"completion_tokens",5,"total_tokens",15)));
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start(); String origin="http://127.0.0.1:"+server.getAddress().getPort();
        var models=new EncryptedModelProfileRepository(jdbc,tx,new ModelSecretBox(new byte[32]),Set.of(origin));
        var model=new ModelProfileService(models).create(admin,new ModelProfileCommand("Fixture",origin+"/v1/chat/completions","test-mixed-provider-key","fixture-v1",1000,100,65536,2,"Fixture enforced bound",1,1,1));
        provider=new OpenAiCompatibleJudgeClient(models); gateway=new ModelGateway(new JdbcModelCallRepository(jdbc,tx,models,quota),provider,clock);
        catalog=new CatalogService(new JdbcCatalogRepository(jdbc,tx,json)); var agents=new AgentService(new JdbcAgentRepository(jdbc,tx));
        experiments=new ExperimentService(new JdbcExperimentRepository(jdbc,tx,json),catalog,agents,clock);
        evidence=new EvidenceService(new JdbcEvidenceRepository(jdbc,tx,json),clock); repository=new JdbcEvaluationRepository(jdbc,tx,json);
        var dataset=catalog.create(owner,ResourceKind.DATASET,"Orders",json.readTree("{\"cases\":[{\"instruction\":\"Check order amount\",\"input\":{},\"expected\":100}]}"));
        var rule=catalog.create(owner,ResourceKind.EVALUATOR,"Amount",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"amount\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{\"operator\":\"eq\",\"selector\":\"/output/amount\",\"expected\":100}}"));
        var python=catalog.create(owner,ResourceKind.EVALUATOR,"Custom",json.readTree("{\"type\":\"PYTHON\",\"criterion_id\":\"custom\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{\"code\":\"def evaluate(context): pass\"}}"));
        var llm=json.createObjectNode().put("type","LLM").put("criterion_id","coverage").put("threshold",0.75); llm.putArray("required_evidence").add("task_instruction").add("output");
        var config=llm.putObject("config").put("model_profile_id",model.id().toString()).put("price_version_id",model.price().id().toString()).put("prompt_version","rubric-v1").put("max_input_bytes",16384).put("max_output_tokens",50);
        var rubric=config.putObject("rubric").put("metric","Objective coverage"); var levels=rubric.putArray("levels"); for(int grade=0;grade<=4;grade++) levels.addObject().put("grade",grade).put("description","Coverage level "+grade);
        var judge=catalog.create(owner,ResourceKind.EVALUATOR,"Coverage",llm);
        ruleDraft=rule.id(); judgeDraft=judge.id();
        var suiteContent=json.createObjectNode(); var entries=suiteContent.putArray("entries");
        for(var item:List.of(rule,python,judge)) entries.addObject().put("evaluator_version",catalog.publish(owner,item.id()).id().toString()).put("dimension",item==python?"process":"outcome").put("required_to_pass",item!=python).put("weight",1);
        suite=catalog.publish(owner,catalog.create(owner,ResourceKind.SUITE,"Mixed",suiteContent).id()).id(); var agent=agents.create(owner,"orders","v1");
        experiment=experiments.start(owner,new StartExperimentCommand(agent.id(),"v1",catalog.publish(owner,dataset.id()).id(),suite,1,clock.instant().plusSeconds(3600),UUID.randomUUID().toString()));
        trial=experiments.trials(owner,experiment).getFirst().id();
        jdbc.sql("update trials set status='SUCCEEDED',completed_at=?,completion=cast(? as jsonb) where id=?").params(java.sql.Timestamp.from(clock.instant()),"{\"status\":\"SUCCEEDED\",\"output\":{\"amount\":100},\"final_sequence\":0,\"event_count\":0,\"dropped_count\":0,\"artifact_ids\":[]}",trial).update();
        snapshot=evidence.freeze(owner,trial,false).id(); restart();
    }
    void restart() {
        evaluations=new EvaluationOrchestrator(repository,evidence,Map.of("RULE",(Evaluator)(definition,context) -> { ruleCount.incrementAndGet(); return new RuleEvaluator().evaluate(definition,context); },
            "PYTHON",new PythonEvaluator((job,context) -> { pythonCount.incrementAndGet(); return new CriterionResult(job.definition().criterionId(),CriterionStatus.ERROR,null,null,"TIMEOUT",List.of(),job.definition().evaluatorVersion()); }),"LLM",new LlmEvaluator(gateway)),clock);
    }
    void drain() { evaluations.schedule(100); for(int i=0;i<100 && evaluations.runNext();i++) {} }
    @AfterEach void stop() { provider.close(); server.stop(0); }
    @Test void resumesOnlyQuotaBlockedJobsAndPreservesEvidenceVersions() throws Exception {
        drain(); assertThat(ruleCount.get()).isEqualTo(1); assertThat(pythonCount.get()).isEqualTo(1); assertThat(providerCount.get()).isZero();
        assertThat(jdbc.sql("select count(*) from evaluation_jobs where workspace_id=? and status='WAITING_QUOTA'").param(owner.workspaceId()).query(Long.class).single()).isEqualTo(1);
        var python=json.readTree(jdbc.sql("select payload::text from evaluation_results where workspace_id=? and payload->>'criterion_id'='custom'").param(owner.workspaceId()).query(String.class).single());
        assertThat(python.path("status").asText()).isEqualTo("ERROR"); assertThat(python.path("score").isNull()).isTrue();
        quota.grant(admin,owner.workspaceId(),5000,"Budget",UUID.randomUUID()); clock.advance(6); restart(); drain();
        assertThat(ruleCount.get()).isEqualTo(1); assertThat(pythonCount.get()).isEqualTo(1); assertThat(providerCount.get()).isEqualTo(1); assertThat(quota.wallet(owner).consumed()).isEqualTo(15);
        assertThat(new ReportService(repository,new EvaluationAggregator()).get(owner,experiment).passedAttempts()).isEqualTo(1);
    }
    @Test void cancelledWaitingExperimentNeverStartsJudgeAfterGrant() {
        drain(); experiments.cancel(owner,experiment); quota.grant(admin,owner.workspaceId(),5000,"Budget",UUID.randomUUID()); clock.advance(10); restart(); drain();
        assertThat(providerCount.get()).isZero(); assertThat(quota.wallet(owner).reserved()).isZero();
        assertThat(ruleCount.get()).isEqualTo(1); assertThat(pythonCount.get()).isEqualTo(1);
    }
    @Test void cancellationBetweenClaimAndGatewayDispatchPreventsProviderSend() {
        quota.grant(admin,owner.workspaceId(),5000,"Budget",UUID.randomUUID()); evaluations.schedule(100);
        var rule=repository.claim(clock.instant()).orElseThrow();
        repository.complete(rule,new RuleEvaluator().evaluate(rule.definition(),evidence.context(owner,snapshot)),clock.instant());
        var python=repository.claim(clock.instant()).orElseThrow();
        repository.complete(python,new CriterionResult(python.definition().criterionId(),CriterionStatus.ERROR,null,null,"TIMEOUT",List.of(),python.definition().evaluatorVersion()),clock.instant());
        var judge=repository.claim(clock.instant()).orElseThrow(); assertThat(judge.definition().type()).isEqualTo("LLM");
        experiments.cancel(owner,experiment);
        new LlmEvaluator(gateway).evaluate(judge,evidence.context(owner,snapshot));
        assertThat(providerCount.get()).isZero(); assertThat(quota.wallet(owner).reserved()).isZero(); assertThat(quota.wallet(owner).consumed()).isZero();
    }
    @Test void explicitReevaluationIsIdempotentAndOnlyLateOptInChangesSnapshot() throws Exception {
        quota.grant(admin,owner.workspaceId(),10000,"Budget",UUID.randomUUID()); drain();
        var reevaluate=new ReevaluationService(new JdbcReevaluationRepository(jdbc,tx),catalog,evidence,clock);
        UUID oldRun=reevaluate.create(owner,trial,suite,false,"same-request");
        var ids=new HashSet<UUID>();
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<java.util.concurrent.Future<UUID>>();
            for(int i=0;i<20;i++) futures.add(pool.submit(()->reevaluate.create(owner,trial,suite,false,"same-request")));
            for(var future:futures) ids.add(future.get(20,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(ids).containsExactly(oldRun);
        assertThat(jdbc.sql("select snapshot_id from grading_jobs where id=?").param(oldRun).query(UUID.class).single()).isEqualTo(snapshot);
        clock.advance(31); var event=new TraceEvent("1.0",UUID.randomUUID(),trial,"1".repeat(32),"1".repeat(16),null,EventType.LOG,SpanKind.AGENT,"late observation",1,clock.instant(),json.createObjectNode().put("message","late"),new CaptureMetadata(false,false));
        String raw=new ProtocolCodec().encode(event);
        jdbc.sql("insert into trace_events(workspace_id,trial_id,event_id,sequence,event_json,content_hash,received_at) values(?,?,?,?,cast(? as jsonb),?,?)")
            .params(owner.workspaceId(),trial,event.eventId(),1,raw,CanonicalJson.hash(json.readTree(raw)),java.sql.Timestamp.from(clock.instant())).update();
        assertThat(reevaluate.create(owner,trial,suite,false,"same-request")).isEqualTo(oldRun);
        UUID lateRun=reevaluate.create(owner,trial,suite,true,"include-late");
        UUID changed=jdbc.sql("select snapshot_id from grading_jobs where id=?").param(lateRun).query(UUID.class).single();
        assertThat(changed).isNotEqualTo(snapshot); assertThat(evidence.load(owner,changed).contentHash()).isNotEqualTo(evidence.load(owner,snapshot).contentHash());
        assertThat(jdbc.sql("select count(*) from grading_jobs where trial_id=?").param(trial).query(Long.class).single()).isEqualTo(3);
        drain();
        assertThat(jdbc.sql("select count(*) from evaluation_results where trial_id=?").param(trial).query(Long.class).single()).isEqualTo(9);
        assertThat(jdbc.sql("select count(*) from trials where experiment_id=?").param(experiment).query(Long.class).single()).isEqualTo(1);
        assertThat(new ReportService(repository,new EvaluationAggregator()).trials(owner,experiment,null,100).items().getFirst().runId()).isEqualTo(lateRun);
        assertThat(providerCount.get()).isEqualTo(3); assertThat(quota.wallet(owner).consumed()).isEqualTo(45);
    }
    @Test void reportUsesReevaluationSuiteInsteadOfOriginalWeights() throws Exception {
        quota.grant(admin,owner.workspaceId(),5000,"Budget",UUID.randomUUID()); drain();
        var stricter=catalog.create(owner,ResourceKind.EVALUATOR,"Stricter",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"changed_amount\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{\"operator\":\"eq\",\"selector\":\"/output/amount\",\"expected\":99}}"));
        var content=json.createObjectNode(); content.putArray("entries").addObject().put("evaluator_version",catalog.publish(owner,stricter.id()).id().toString()).put("dimension","outcome").put("required_to_pass",true).put("weight",1);
        UUID replacement=catalog.publish(owner,catalog.create(owner,ResourceKind.SUITE,"Replacement",content).id()).id();
        var service=new ReevaluationService(new JdbcReevaluationRepository(jdbc,tx),catalog,evidence,clock);
        service.create(owner,trial,replacement,false,"new-suite"); drain();
        var reports=new ReportService(repository,new EvaluationAggregator());
        assertThat(reports.get(owner,experiment).failedAttempts()).isEqualTo(1);
        assertThat(reports.get(owner,experiment).dimensions().get("outcome").score()).isEqualByComparingTo("0");
        assertThat(reports.trials(owner,experiment,null,100).items().getFirst().suiteVersionId()).isEqualTo(replacement);
        assertThat(reports.get(owner,experiment).evaluationSuiteVersionIds()).containsExactly(replacement);
        assertThat(providerCount.get()).isEqualTo(1);
    }
    @Test void draftPreviewFreezesCopyWithoutPublishingSourceOrChangingExperimentReport() {
        quota.grant(admin,owner.workspaceId(),5000,"Budget",UUID.randomUUID()); drain();
        var draft=catalog.draft(owner,ruleDraft); var changed=draft.content().deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("config")).put("expected",99);
        var edited=catalog.update(owner,ruleDraft,draft.revision(),draft.name(),changed);
        long versions=jdbc.sql("select count(*) from catalog_versions where resource_id=?").param(ruleDraft).query(Long.class).single();
        var regrades=new ReevaluationService(new JdbcReevaluationRepository(jdbc,tx),catalog,evidence,clock);
        var previews=new EvaluatorPreviewService(new JdbcEvaluatorPreviewRepository(jdbc,tx),catalog,regrades,clock);
        UUID run=previews.create(owner,ruleDraft,edited.revision(),trial,"draft-preview");
        assertThat(previews.create(owner,ruleDraft,edited.revision(),trial,"draft-preview")).isEqualTo(run);
        drain();
        assertThat(jdbc.sql("select count(*) from catalog_versions where resource_id=?").param(ruleDraft).query(Long.class).single()).isEqualTo(versions);
        var view=new EvaluationRunService(new JdbcEvaluationRunRepository(jdbc,tx)).get(owner,run);
        assertThat(view.purpose()).isEqualTo("PREVIEW"); assertThat(view.criteria()).hasSize(1); assertThat(view.criteria().getFirst().result().score()).isEqualByComparingTo("0");
        assertThat(new ReportService(repository,new EvaluationAggregator()).get(owner,experiment).passedAttempts()).isEqualTo(1);
        assertThat(providerCount.get()).isEqualTo(1);
    }
    @Test void modelPreviewUsesTheSameQuotaAndPersistentCallPath() {
        quota.grant(admin,owner.workspaceId(),5000,"Budget",UUID.randomUUID()); drain();
        var previews=new EvaluatorPreviewService(new JdbcEvaluatorPreviewRepository(jdbc,tx),catalog,new ReevaluationService(new JdbcReevaluationRepository(jdbc,tx),catalog,evidence,clock),clock);
        var draft=catalog.draft(owner,judgeDraft); var run=previews.create(owner,judgeDraft,draft.revision(),trial,"judge-preview"); drain();
        assertThat(previews.create(owner,judgeDraft,draft.revision(),trial,"judge-preview")).isEqualTo(run); drain();
        assertThat(providerCount.get()).isEqualTo(2); assertThat(quota.wallet(owner).consumed()).isEqualTo(30);
        assertThat(new ReportService(repository,new EvaluationAggregator()).trials(owner,experiment,null,100).items().getFirst().runId()).isNotEqualTo(run);
    }
    @Test void firstEvidencePreviewDoesNotSuppressAutomaticSuiteEvaluation() {
        jdbc.sql("delete from grading_jobs where trial_id=?").param(trial).update();
        jdbc.sql("delete from evidence_snapshots where trial_id=?").param(trial).update();
        var previews=new EvaluatorPreviewService(new JdbcEvaluatorPreviewRepository(jdbc,tx),catalog,new ReevaluationService(new JdbcReevaluationRepository(jdbc,tx),catalog,evidence,clock),clock);
        var draft=catalog.draft(owner,ruleDraft); previews.create(owner,ruleDraft,draft.revision(),trial,"first-evidence");
        assertThat(jdbc.sql("select count(*) from grading_jobs where trial_id=? and purpose='INITIAL'").param(trial).query(Long.class).single()).isEqualTo(1);
        drain(); assertThat(new ReportService(repository,new EvaluationAggregator()).trials(owner,experiment,null,100).items().getFirst().runId()).isNotNull();
    }
    @Test void hardFailureRemainsFailureWhileJudgeWaitsForCredits() throws Exception {
        var stricter=catalog.create(owner,ResourceKind.EVALUATOR,"Required failure",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"required_failure\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{\"operator\":\"eq\",\"selector\":\"/output/amount\",\"expected\":99}}"));
        var content=json.createObjectNode(); var entries=content.putArray("entries"); entries.addObject().put("evaluator_version",catalog.publish(owner,stricter.id()).id().toString()).put("dimension","outcome").put("required_to_pass",true).put("weight",1);
        entries.add(catalog.version(owner,suite).content().path("entries").get(2));
        UUID replacement=catalog.publish(owner,catalog.create(owner,ResourceKind.SUITE,"Fail plus wait",content).id()).id();
        new ReevaluationService(new JdbcReevaluationRepository(jdbc,tx),catalog,evidence,clock).create(owner,trial,replacement,false,"hard-failure"); drain();
        var report=new ReportService(repository,new EvaluationAggregator()).get(owner,experiment);
        assertThat(report.failedAttempts()).isEqualTo(1); assertThat(report.dimensions().get("outcome").coverage()).isEqualByComparingTo("0.5"); assertThat(providerCount.get()).isZero();
    }
    static final class MutableClock extends Clock {
        private Instant now=Instant.now();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long seconds) { now=now.plusSeconds(seconds); }
    }
}
