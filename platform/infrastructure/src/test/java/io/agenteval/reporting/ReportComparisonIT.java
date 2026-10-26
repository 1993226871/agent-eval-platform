package io.agenteval.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenteval.agents.*;
import io.agenteval.catalog.*;
import io.agenteval.evaluation.*;
import io.agenteval.experiments.*;
import io.agenteval.identity.*;
import io.agenteval.telemetry.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReportComparisonIT {
    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static DataSourceTransactionManager transactions;
    final ObjectMapper json = new ObjectMapper();
    final Instant now = Instant.now();
    WorkspaceActor owner;
    CatalogService catalog;
    ExperimentService experiments;
    EvidenceService evidence;
    EvaluationOrchestrator grading;
    ComparisonService comparisons;
    ReportExporter exporter;
    UUID dataset, suite, evaluator, agent;

    @BeforeAll static void database() {
        postgres = new PostgreSQLContainer<>("postgres:17.11"); postgres.start();
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate(); jdbc = JdbcClient.create(source); transactions = new DataSourceTransactionManager(source);
    }
    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }
    WorkspaceActor user() { return new IdentityService(new JdbcIdentityRepository(jdbc, transactions), new BCryptPasswordEncoder(4)).createUser(UUID.randomUUID()+"@comparison.local", "Temporary-Password-1", false).actor(); }
    @BeforeEach void setup() throws Exception {
        owner = user(); catalog = new CatalogService(new JdbcCatalogRepository(jdbc, transactions, json));
        var agents = new AgentService(new JdbcAgentRepository(jdbc,transactions)); agent = agents.create(owner, "orders", "v1").id();
        experiments = new ExperimentService(new JdbcExperimentRepository(jdbc, transactions, json), catalog, agents, Clock.fixed(now, ZoneOffset.UTC));
        evidence = new EvidenceService(new JdbcEvidenceRepository(jdbc, transactions, json), Clock.fixed(now, ZoneOffset.UTC));
        var repository = new JdbcEvaluationRepository(jdbc, transactions, json);
        grading = new EvaluationOrchestrator(repository, evidence, new RuleEvaluator(), Clock.fixed(now, ZoneOffset.UTC));
        var queries = new JdbcReportQueries(jdbc, transactions, json, repository);
        comparisons = new ComparisonService(queries, new EvaluationAggregator());
        exporter = new ReportExporter(queries, new EvaluationAggregator());
        dataset = catalog.publish(owner, catalog.create(owner, ResourceKind.DATASET, "Orders", json.readTree("{\"cases\":[{\"instruction\":\"Check amount\",\"input\":{}},{\"instruction\":\"Check amount\",\"input\":{}},{\"instruction\":\"Check amount\",\"input\":{}}]}")).id()).id();
        var rule = catalog.create(owner, ResourceKind.EVALUATOR, "Amount", json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"amount\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{\"operator\":\"eq\",\"selector\":\"/output/amount\",\"expected\":100}}"));
        evaluator = catalog.publish(owner, rule.id()).id(); suite = suite(evaluator);
    }
    UUID suite(UUID version) throws Exception {
        return catalog.publish(owner, catalog.create(owner, ResourceKind.SUITE, "Suite", json.readTree("{\"entries\":[{\"evaluator_version\":\""+version+"\",\"dimension\":\"outcome\",\"required_to_pass\":true,\"weight\":1}]}")).id()).id();
    }
    UUID run(UUID datasetVersion, UUID suiteVersion) {
        UUID experiment = experiments.start(owner, new StartExperimentCommand(agent, "v1", datasetVersion, suiteVersion, 1, now.plusSeconds(3600), UUID.randomUUID().toString()));
        for (var trial : experiments.trials(owner, experiment)) {
            String output = switch(trial.caseIndex()) { case 0 -> "{\"amount\":100}"; case 1 -> "{\"amount\":80}"; default -> "{}"; };
            jdbc.sql("update trials set status='SUCCEEDED',started_at=?,completed_at=?,completion=cast(? as jsonb) where id=?").params(java.sql.Timestamp.from(now.minusSeconds(60)), java.sql.Timestamp.from(now.minusSeconds(31)), "{\"status\":\"SUCCEEDED\",\"output\":"+output+",\"final_sequence\":0,\"event_count\":0,\"dropped_count\":0,\"artifact_ids\":[]}", trial.id()).update();
            evidence.freeze(owner, trial.id(), false);
        }
        grading.schedule(100); while (grading.runNext()) {}
        return experiment;
    }
    @Test void exposesCoverageAndRejectsFalseComparability() throws Exception {
        UUID left = run(dataset, suite), right = run(dataset, suite);
        var same = comparisons.compare(owner, left, right);
        assertThat(same.directlyComparable()).isTrue();
        assertThat(same.left().passRatePlanned().denominator()).isEqualTo(3);
        assertThat(same.left().passRateDeterminable().denominator()).isEqualTo(2);
        assertThat(same.left().dimensions().get("outcome").coverage()).isEqualByComparingTo("0.666667");
        var modified = catalog.create(owner, ResourceKind.EVALUATOR, "Other", json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"amount\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{\"operator\":\"eq\",\"selector\":\"/output/amount\",\"expected\":80}}"));
        UUID different = run(dataset, suite(catalog.publish(owner, modified.id()).id()));
        var comparison = comparisons.compare(owner, left, different);
        assertThat(comparison.directlyComparable()).isFalse();
        assertThat(comparison.reasons()).contains("EVALUATOR_VERSION_MISMATCH", "SUITE_VERSION_MISMATCH");
        assertThat(comparison.dimensionDeltas()).isEmpty();
        assertThatThrownBy(() -> comparisons.compare(user(), left, right)).isInstanceOf(ScopeViolationException.class);
        UUID result = jdbc.sql("select job_id from evaluation_results where run_id in (select id from grading_jobs where trial_id in (select id from trials where experiment_id=?)) limit 1").param(left).query(UUID.class).single();
        var reviews = new HumanReviewService(new JdbcHumanReviewRepository(jdbc, transactions), Clock.systemUTC());
        reviews.add(owner, result, new BigDecimal("0.25"), "Human calibration");
        var exported = exporter.export(owner, left);
        assertThat(exported.reviews()).hasSize(1);
        assertThat(exported.resultIdentities()).anyMatch(identity -> identity.resultId().equals(result) && identity.evaluatorVersion().equals(evaluator));
        assertThat(exported.reviews().getFirst().score()).isEqualByComparingTo("0.25");
        assertThat(exported.trials().getFirst().criteria().getFirst().score()).isEqualByComparingTo("1");
        assertThat(exported.evidence()).hasSize(3);
        assertThat(exported.report().passRatePlanned().denominator()).isEqualTo(3);
        assertThatThrownBy(() -> exporter.export(user(), left)).isInstanceOf(ScopeViolationException.class);
    }
    @Test void detectsDatasetModelPriceAndRevisedEvidenceDifferences() throws Exception {
        UUID left=run(dataset,suite), right=run(dataset,suite);
        UUID trial=experiments.trials(owner,right).getFirst().id();
        var event=new io.agenteval.protocol.TraceEvent("1.0",UUID.randomUUID(),trial,"1".repeat(32),"1".repeat(16),null,io.agenteval.protocol.EventType.LOG,io.agenteval.protocol.SpanKind.AGENT,"late evidence",1,now,json.createObjectNode().put("message","late"),new io.agenteval.protocol.CaptureMetadata(false,false));
        String raw=new io.agenteval.protocol.ProtocolCodec().encode(event);
        jdbc.sql("insert into trace_events(workspace_id,trial_id,event_id,sequence,event_json,content_hash,received_at) values(?,?,?,?,cast(? as jsonb),?,?)").params(owner.workspaceId(),trial,event.eventId(),1,raw,CanonicalJson.hash(json.readTree(raw)),java.sql.Timestamp.from(now)).update();
        new ReevaluationService(new JdbcReevaluationRepository(jdbc,transactions),catalog,evidence,Clock.fixed(now,ZoneOffset.UTC)).create(owner,trial,suite,true,"include-late");
        grading.schedule(100); while(grading.runNext()) {}
        var changed=comparisons.compare(owner,left,right);
        assertThat(changed.directlyComparable()).isFalse(); assertThat(changed.reasons()).contains("EVIDENCE_SCOPE_MISMATCH");
        var copied=catalog.create(owner,ResourceKind.DATASET,"Copied dataset",catalog.version(owner,dataset).content());
        UUID otherDataset=run(catalog.publish(owner,copied.id()).id(),suite);
        assertThat(comparisons.compare(owner,left,otherDataset).reasons()).contains("DATASET_VERSION_MISMATCH");
        var judges=new ArrayList<UUID>();
        for(int i=0;i<2;i++) {
            var content=json.createObjectNode().put("type","LLM").put("criterion_id","judge").put("threshold",1); content.putArray("required_evidence").add("output");
            content.putObject("config").put("model_profile_id",UUID.randomUUID().toString()).put("price_version_id",UUID.randomUUID().toString()).put("rubric","Coverage").put("scale_max",4);
            UUID version=catalog.publish(owner,catalog.create(owner,ResourceKind.EVALUATOR,"Judge",content).id()).id();
            judges.add(run(dataset,suite(version)));
        }
        assertThat(comparisons.compare(owner,judges.get(0),judges.get(1)).reasons()).contains("MODEL_PRICE_VERSION_MISMATCH","NO_DETERMINABLE_DATA");
    }
    @Test void summaryAndSmallTrialPageDoNotLoadEveryCriterionBody() {
        UUID experiment=run(dataset,suite);
        long largest=jdbc.sql("select max(octet_length(r.payload::text)) from evaluation_results r join trials t on t.id=r.trial_id where t.experiment_id=?").param(experiment).query(Long.class).single();
        var repository=new JdbcEvaluationRepository(jdbc,transactions,json,largest+64);
        assertThatThrownBy(() -> repository.load(owner,experiment)).isInstanceOf(io.agenteval.administration.ResourceReadLimitException.class);
        var summary=repository.summary(owner,experiment);
        assertThat(summary.trials()).hasSize(3);
        assertThat(summary.trials().getFirst().results().values()).allSatisfy(result -> { assertThat(result.evidenceRefs()).isEmpty(); assertThat(result.reason()).isEqualTo("Summary projection"); });
        var service=new ReportService(repository,new EvaluationAggregator());
        assertThat(service.get(owner,experiment).plannedAttempts()).isEqualTo(3);
        var page=service.trials(owner,experiment,null,1);
        assertThat(page.items()).hasSize(1); assertThat(page.items().getFirst().criteria().getFirst().reason()).isNotEqualTo("Summary projection");
        assertThat(page.nextCursor()).isNotNull(); assertThat(service.trials(owner,experiment,page.nextCursor(),1).items()).hasSize(1);
        assertThatThrownBy(() -> repository.summary(user(),experiment)).isInstanceOf(ScopeViolationException.class);
    }
    @Test void rejectsOversizedExportBeforeLoadingPrivateBodies() {
        UUID experiment=run(dataset,suite);
        var queries=new JdbcReportQueries(jdbc,transactions,json,new JdbcEvaluationRepository(jdbc,transactions,json));
        var limited=new ReportExporter(queries,new EvaluationAggregator(),128);
        assertThatThrownBy(() -> limited.export(owner,experiment)).isInstanceOf(ReportExportLimitException.class);
        assertThatThrownBy(() -> limited.export(user(),experiment)).isInstanceOf(ScopeViolationException.class);
    }
}
