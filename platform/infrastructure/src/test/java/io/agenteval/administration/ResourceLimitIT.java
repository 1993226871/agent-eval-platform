package io.agenteval.administration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenteval.agents.*;
import io.agenteval.catalog.*;
import io.agenteval.experiments.*;
import io.agenteval.identity.*;
import io.agenteval.protocol.*;
import io.agenteval.workers.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ResourceLimitIT {
    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static DataSourceTransactionManager transactions;
    final ObjectMapper json=new ObjectMapper();
    final Instant now=Instant.now();
    IdentityService identity;
    CatalogService catalog;
    AgentService agents;
    ExperimentService experiments;
    JdbcWorkerRepository workers;
    JdbcLeaseRepository leases;
    ResourceLimitService limits;
    @BeforeAll static void database() {
        postgres=new PostgreSQLContainer<>("postgres:17.11"); postgres.start();
        var source=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate(); jdbc=JdbcClient.create(source); transactions=new DataSourceTransactionManager(source);
    }
    @AfterAll static void stop() { if(postgres!=null) postgres.stop(); }
    @BeforeEach void setup() {
        // These are only this class's disposable database rows; isolate runtime capacity across test methods.
        jdbc.sql("update worker_instances set last_seen_at=?").param(java.sql.Timestamp.from(now.minusSeconds(120))).update();
        jdbc.sql("update trials set status='CANCELLED' where status in ('LEASED','RUNNING')").update();
        identity=new IdentityService(new JdbcIdentityRepository(jdbc,transactions),new BCryptPasswordEncoder(4));
        catalog=new CatalogService(new JdbcCatalogRepository(jdbc,transactions,json)); agents=new AgentService(new JdbcAgentRepository(jdbc,transactions));
        experiments=new ExperimentService(new JdbcExperimentRepository(jdbc,transactions,json),catalog,agents,Clock.fixed(now,ZoneOffset.UTC));
        workers=new JdbcWorkerRepository(jdbc,transactions); leases=new JdbcLeaseRepository(jdbc,transactions,json);
        limits=new ResourceLimitService(new JdbcResourceLimitRepository(jdbc,transactions));
    }
    WorkspaceActor user(boolean admin) { return identity.createUser(UUID.randomUUID()+"@limits.local","Temporary-Password-1",admin).actor(); }
    record Setup(WorkspaceActor owner,UUID agent,WorkerPrincipal principal,UUID dataset,UUID suite) {}
    Setup fixture(boolean withCatalog) throws Exception {
        var owner=user(false); var agent=agents.create(owner,"Orders","v1");
        var credentials=new WorkerCredentialService(workers,agents); var issued=credentials.issue(owner,Set.of(agent.id()));
        UUID dataset=null,suite=null;
        if(withCatalog) {
            dataset=catalog.publish(owner,catalog.create(owner,ResourceKind.DATASET,"Orders",json.readTree("{\"cases\":[{\"instruction\":\"Check\",\"input\":{}}]}")).id()).id();
            UUID evaluator=catalog.publish(owner,catalog.create(owner,ResourceKind.EVALUATOR,"Rule",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"check\",\"threshold\":1,\"required_evidence\":[\"output\"],\"config\":{\"operator\":\"exists\",\"selector\":\"/output\"}}")).id()).id();
            suite=catalog.publish(owner,catalog.create(owner,ResourceKind.SUITE,"Suite",json.readTree("{\"entries\":[{\"evaluator_version\":\""+evaluator+"\",\"dimension\":\"outcome\",\"required_to_pass\":true,\"weight\":1}]}")).id()).id();
        }
        return new Setup(owner,agent.id(),credentials.authenticate(issued.secret()),dataset,suite);
    }
    RegisterWorkerRequest registration(Setup setup,String instance) { return new RegisterWorkerRequest(setup.agent(),"v1",instance,"1.0","JAVA","test",8); }
    StartExperimentCommand command(Setup setup,int repeat) { return new StartExperimentCommand(setup.agent(),"v1",setup.dataset(),setup.suite(),repeat,now.plusSeconds(3600),UUID.randomUUID().toString()); }
    @Test void enforcesConcurrentLimitsAtomically() throws Exception {
        var setup=fixture(true); var admin=user(true);
        limits.configure(admin,setup.owner().workspaceId(),new ResourceLimits(2,1,1024,2,10,20,5,20,60),"Workspace limit fixture");
        var original=command(setup,1); UUID first=experiments.start(setup.owner(),original);
        int admitted=0,rejected=0;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var calls=new ArrayList<Future<UUID>>(); for(int i=0;i<20;i++) calls.add(pool.submit(() -> experiments.start(setup.owner(),command(setup,1))));
            for(var call:calls) try { call.get(20,TimeUnit.SECONDS); admitted++; } catch(ExecutionException failure) { assertThat(failure.getCause()).isInstanceOf(CapacityExceededException.class); rejected++; }
        }
        assertThat(admitted).isEqualTo(1); assertThat(rejected).isEqualTo(19);
        assertThat(experiments.start(setup.owner(),original)).isEqualTo(first);
        assertThat(limits.get(setup.owner()).usage().trialExecutions()).isEqualTo(2);
        var one=workers.register(setup.principal(),registration(setup,"one"),now);
        var two=workers.register(setup.principal(),registration(setup,"two"),now);
        assertThatThrownBy(() -> workers.register(setup.principal(),registration(setup,"three"),now)).isInstanceOf(CapacityExceededException.class);
        var grant=leases.claim(setup.principal(),new ClaimRequest(one.workerId(),setup.agent(),"v1",0),now).orElseThrow();
        assertThat(leases.claim(setup.principal(),new ClaimRequest(two.workerId(),setup.agent(),"v1",0),now)).isEmpty();
        leases.started(setup.principal(),grant.trialId(),grant.attemptToken(),now);
        leases.complete(setup.principal(),grant.trialId(),grant.attemptToken(),new CompletionRequest(TrialExecutionStatus.SUCCEEDED,json.createObjectNode(),0,0,0,List.of()),now);
        assertThat(leases.claim(setup.principal(),new ClaimRequest(two.workerId(),setup.agent(),"v1",0),now)).isPresent();
        assertThatThrownBy(() -> limits.configure(setup.owner(),setup.owner().workspaceId(),ResourceLimits.defaults(),"Unauthorized")).isInstanceOf(ScopeViolationException.class);
    }
    @Test void enforcesFiftyOnlineWorkersIncludingOfflineReactivation() throws Exception {
        var fixtures=new ArrayList<Setup>(); for(int i=0;i<51;i++) fixtures.add(fixture(false));
        var admitted=new ArrayList<Setup>(); var denied=new ArrayList<Setup>();
        var ready=new CountDownLatch(51); var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var calls=new ArrayList<Future<WorkerRegistration>>();
            for(var setup:fixtures) calls.add(pool.submit(() -> { ready.countDown(); start.await(); return workers.register(setup.principal(),registration(setup,"worker"),now); }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            for(int i=0;i<calls.size();i++) try { calls.get(i).get(30,TimeUnit.SECONDS); admitted.add(fixtures.get(i)); }
            catch(ExecutionException failure) { assertThat(failure.getCause()).isInstanceOf(CapacityExceededException.class); denied.add(fixtures.get(i)); }
        }
        assertThat(admitted).hasSize(50); assertThat(denied).hasSize(1); var first=admitted.getFirst();
        jdbc.sql("update worker_instances set last_seen_at=? where workspace_id=?").params(java.sql.Timestamp.from(now.minusSeconds(120)),first.owner().workspaceId()).update();
        workers.register(denied.getFirst().principal(),registration(denied.getFirst(),"worker"),now);
        assertThatThrownBy(() -> workers.register(first.principal(),registration(first,"worker"),now)).isInstanceOf(CapacityExceededException.class);
        assertThat(jdbc.sql("select count(*) from worker_instances where last_seen_at>?").param(java.sql.Timestamp.from(now.minusSeconds(60))).query(Long.class).single()).isEqualTo(50);
    }
    @Test void queuesBeyondTwentyRunningTrialsAndResumesAfterCompletion() throws Exception {
        var pending=new ArrayList<Map.Entry<Setup,UUID>>();
        for(int i=0;i<12;i++) {
            var setup=fixture(true); experiments.start(setup.owner(),command(setup,2));
            var worker=workers.register(setup.principal(),registration(setup,"worker"),now);
            pending.add(Map.entry(setup,worker.workerId()));
        }
        var active=new ArrayList<Map.Entry<Setup,LeaseGrant>>(); var blocked=new ArrayList<Map.Entry<Setup,UUID>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var requests=new ArrayList<Map.Entry<Setup,UUID>>(); var calls=new ArrayList<Future<Optional<LeaseGrant>>>(); var start=new CountDownLatch(1);
            for(var worker:pending) for(int repeat=0;repeat<2;repeat++) {
                requests.add(worker); calls.add(pool.submit(() -> { start.await(); var setup=worker.getKey(); return leases.claim(setup.principal(),new ClaimRequest(worker.getValue(),setup.agent(),"v1",0),now); }));
            }
            start.countDown();
            for(int i=0;i<calls.size();i++) { var grant=calls.get(i).get(30,TimeUnit.SECONDS); var request=requests.get(i); if(grant.isPresent()) active.add(Map.entry(request.getKey(),grant.get())); else blocked.add(request); }
        }
        assertThat(active).hasSize(20); assertThat(blocked).hasSize(4);
        var first=active.getFirst(); leases.started(first.getKey().principal(),first.getValue().trialId(),first.getValue().attemptToken(),now);
        leases.complete(first.getKey().principal(),first.getValue().trialId(),first.getValue().attemptToken(),new CompletionRequest(TrialExecutionStatus.SUCCEEDED,json.createObjectNode(),0,0,0,List.of()),now);
        var waiting=blocked.getFirst(); assertThat(leases.claim(waiting.getKey().principal(),new ClaimRequest(waiting.getValue(),waiting.getKey().agent(),"v1",0),now)).isPresent();
    }
    @Test void limitsFreeRuleWorkAndResumesOnlyUnscheduledJobsAfterIncrease() throws Exception {
        var setup=fixture(true); var admin=user(true);
        limits.configure(admin,setup.owner().workspaceId(),new ResourceLimits(5,2,1073741824L,200,300,1000,10000,1,3600),"One evaluation job");
        UUID experiment=experiments.start(setup.owner(),command(setup,2));
        var evidence=new io.agenteval.telemetry.EvidenceService(new io.agenteval.telemetry.JdbcEvidenceRepository(jdbc,transactions,json),Clock.fixed(now,ZoneOffset.UTC));
        for(var trial:experiments.trials(setup.owner(),experiment)) {
            jdbc.sql("update trials set status='SUCCEEDED',completed_at=?,completion=cast(? as jsonb) where id=?").params(java.sql.Timestamp.from(now.minusSeconds(31)),"{\"status\":\"SUCCEEDED\",\"output\":{},\"final_sequence\":0,\"event_count\":0,\"dropped_count\":0,\"artifact_ids\":[]}",trial.id()).update();
            evidence.freeze(setup.owner(),trial.id(),false);
        }
        var repository=new io.agenteval.evaluation.JdbcEvaluationRepository(jdbc,transactions,json);
        var grading=new io.agenteval.evaluation.EvaluationOrchestrator(repository,evidence,new io.agenteval.evaluation.RuleEvaluator(),Clock.fixed(now,ZoneOffset.UTC));
        grading.schedule(100); while(grading.runNext()) {}
        assertThat(jdbc.sql("select count(*) from evaluation_results where workspace_id=?").param(setup.owner().workspaceId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from grading_jobs where workspace_id=? and status='WAITING_RESOURCES'").param(setup.owner().workspaceId()).query(Long.class).single()).isEqualTo(1);
        limits.configure(admin,setup.owner().workspaceId(),new ResourceLimits(5,2,1073741824L,200,300,1000,10000,2,3600),"Increase admission budget");
        grading.schedule(100); while(grading.runNext()) {}
        assertThat(jdbc.sql("select count(*) from evaluation_results where workspace_id=?").param(setup.owner().workspaceId()).query(Long.class).single()).isEqualTo(2);
        assertThat(limits.get(setup.owner()).usage().evaluationJobs()).isEqualTo(2);
    }
    @Test void reservesPythonWorstCaseSecondsOnceAndResumesAfterBudgetIncrease() throws Exception {
        var base=fixture(true); var admin=user(true);
        limits.configure(admin,base.owner().workspaceId(),new ResourceLimits(5,2,1073741824L,200,300,1000,10000,10,30),"One Python execution allowance");
        UUID evaluator=catalog.publish(base.owner(),catalog.create(base.owner(),ResourceKind.EVALUATOR,"Custom",json.readTree("{\"type\":\"PYTHON\",\"criterion_id\":\"custom\",\"threshold\":1,\"required_evidence\":[\"output\"],\"config\":{\"code\":\"def evaluate(context): pass\"}}")).id()).id();
        UUID suite=catalog.publish(base.owner(),catalog.create(base.owner(),ResourceKind.SUITE,"Custom suite",json.readTree("{\"entries\":[{\"evaluator_version\":\""+evaluator+"\",\"dimension\":\"outcome\",\"required_to_pass\":true,\"weight\":1}]}")).id()).id();
        var setup=new Setup(base.owner(),base.agent(),base.principal(),base.dataset(),suite);
        UUID experiment=experiments.start(setup.owner(),command(setup,2));
        var evidence=new io.agenteval.telemetry.EvidenceService(new io.agenteval.telemetry.JdbcEvidenceRepository(jdbc,transactions,json),Clock.fixed(now,ZoneOffset.UTC));
        for(var trial:experiments.trials(setup.owner(),experiment)) {
            jdbc.sql("update trials set status='SUCCEEDED',completed_at=?,completion=cast(? as jsonb) where id=?").params(java.sql.Timestamp.from(now.minusSeconds(31)),"{\"status\":\"SUCCEEDED\",\"output\":{},\"final_sequence\":0,\"event_count\":0,\"dropped_count\":0,\"artifact_ids\":[]}",trial.id()).update();
            evidence.freeze(setup.owner(),trial.id(),false);
        }
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        io.agenteval.evaluation.Evaluator evaluatorFixture=(definition,context) -> {
            calls.incrementAndGet(); return new CriterionResult(definition.criterionId(),CriterionStatus.SCORED,java.math.BigDecimal.ONE,true,"Scheduler fixture, not sandbox acceptance",List.of("output"),definition.evaluatorVersion());
        };
        var repository=new io.agenteval.evaluation.JdbcEvaluationRepository(jdbc,transactions,json);
        var grading=new io.agenteval.evaluation.EvaluationOrchestrator(repository,evidence,Map.of("PYTHON",evaluatorFixture),Clock.fixed(now,ZoneOffset.UTC));
        grading.schedule(100); while(grading.runNext()) {}
        assertThat(calls.get()).isEqualTo(1); assertThat(limits.get(setup.owner()).usage().pythonReservedSeconds()).isEqualTo(30);
        assertThat(jdbc.sql("select count(*) from evaluation_jobs where workspace_id=? and status='DEFERRED' and wait_reason='PYTHON_RESOURCE_LIMIT'").param(setup.owner().workspaceId()).query(Long.class).single()).isEqualTo(1);
        limits.configure(admin,setup.owner().workspaceId(),new ResourceLimits(5,2,1073741824L,200,300,1000,10000,10,60),"Allow another Python execution");
        assertThat(grading.runNext()).isTrue(); assertThat(grading.runNext()).isFalse();
        assertThat(calls.get()).isEqualTo(2); assertThat(limits.get(setup.owner()).usage().pythonReservedSeconds()).isEqualTo(60);
        assertThat(limits.get(setup.owner()).usage().evaluationJobs()).isEqualTo(2);
    }
    @Test void capsCatalogVersionsAgentsCredentialsAndWorkspaceArtifactReservations() throws Exception {
        var setup=fixture(true); var admin=user(true);
        limits.configure(admin,setup.owner().workspaceId(),new ResourceLimits(5,2,2,200,4,4,10000,10,3600),"Small metadata and storage allowance");
        var draft=catalog.create(setup.owner(),ResourceKind.SUITE,"Another suite",catalog.version(setup.owner(),setup.suite()).content());
        assertThatThrownBy(() -> catalog.create(setup.owner(),ResourceKind.SUITE,"Overflow",draft.content())).isInstanceOf(CapacityExceededException.class);
        var version=catalog.publish(setup.owner(),draft.id()); assertThat(catalog.publish(setup.owner(),draft.id()).id()).isEqualTo(version.id());
        catalog.update(setup.owner(),draft.id(),draft.revision(),"Updated suite",draft.content());
        assertThatThrownBy(() -> catalog.publish(setup.owner(),draft.id())).isInstanceOf(CapacityExceededException.class);
        for(int i=0;i<3;i++) agents.create(setup.owner(),"Extra "+i,"v1");
        assertThatThrownBy(() -> agents.create(setup.owner(),"Overflow","v1")).isInstanceOf(CapacityExceededException.class);
        var credentials=new WorkerCredentialService(workers,agents);
        for(int i=0;i<3;i++) credentials.issue(setup.owner(),Set.of(setup.agent()));
        assertThatThrownBy(() -> credentials.issue(setup.owner(),Set.of(setup.agent()))).isInstanceOf(CapacityExceededException.class);
        experiments.start(setup.owner(),command(setup,1));
        var worker=workers.register(setup.principal(),registration(setup,"worker"),now);
        var grant=leases.claim(setup.principal(),new ClaimRequest(worker.workerId(),setup.agent(),"v1",0),now).orElseThrow();
        leases.started(setup.principal(),grant.trialId(),grant.attemptToken(),now);
        var store=org.mockito.Mockito.mock(io.agenteval.telemetry.ArtifactStore.class);
        var artifacts=new io.agenteval.telemetry.ArtifactService(new io.agenteval.telemetry.JdbcArtifactRepository(jdbc,transactions,leases),store,Clock.fixed(now,ZoneOffset.UTC));
        assertThatThrownBy(() -> artifacts.initialize(setup.principal(),grant.trialId(),grant.attemptToken(),new RegisterArtifactRequest("large.txt","text/plain",3,"a".repeat(64),"large"))).isInstanceOf(CapacityExceededException.class);
        assertThat(jdbc.sql("select count(*) from artifacts where workspace_id=?").param(setup.owner().workspaceId()).query(Long.class).single()).isZero();
    }
    @Test void sharesEvidenceByteBudgetWithArtifactsAndDoesNotChargeDuplicateEvents() throws Exception {
        var setup=fixture(true); var admin=user(true);
        limits.configure(admin,setup.owner().workspaceId(),new ResourceLimits(5,2,16384,200,300,1000,10000,10,3600),"Small shared evidence budget");
        experiments.start(setup.owner(),command(setup,1)); var worker=workers.register(setup.principal(),registration(setup,"worker"),now);
        var grant=leases.claim(setup.principal(),new ClaimRequest(worker.workerId(),setup.agent(),"v1",0),now).orElseThrow();
        leases.started(setup.principal(),grant.trialId(),grant.attemptToken(),now);
        var telemetry=new io.agenteval.telemetry.TelemetryService(new io.agenteval.telemetry.JdbcTelemetryRepository(jdbc,transactions,leases,json),Clock.fixed(now,ZoneOffset.UTC));
        var event=new TraceEvent("1.0",UUID.randomUUID(),grant.trialId(),grant.traceId(),"1".repeat(16),null,EventType.LOG,SpanKind.AGENT,"Observation",1,now,json.createObjectNode().put("message","a".repeat(1000)),new CaptureMetadata(false,false));
        telemetry.ingest(setup.principal(),grant.trialId(),grant.attemptToken(),List.of(event));
        long used=limits.get(setup.owner()).usage().evidenceBytes(); assertThat(used).isGreaterThan(1000);
        telemetry.ingest(setup.principal(),grant.trialId(),grant.attemptToken(),List.of(event));
        assertThat(limits.get(setup.owner()).usage().evidenceBytes()).isEqualTo(used);
        var artifacts=new io.agenteval.telemetry.ArtifactService(new io.agenteval.telemetry.JdbcArtifactRepository(jdbc,transactions,leases),org.mockito.Mockito.mock(io.agenteval.telemetry.ArtifactStore.class),Clock.fixed(now,ZoneOffset.UTC));
        assertThatThrownBy(() -> artifacts.initialize(setup.principal(),grant.trialId(),grant.attemptToken(),new RegisterArtifactRequest("overflow.txt","text/plain",16384-used+1,"a".repeat(64),"overflow"))).isInstanceOf(CapacityExceededException.class);
        assertThat(jdbc.sql("select count(*) from trace_events where trial_id=?").param(grant.trialId()).query(Long.class).single()).isEqualTo(1);
    }
    @Test void reportsStorageWaitWithoutZeroScoreAndFreezesAfterIncrease() throws Exception {
        var setup=fixture(true); var admin=user(true);
        limits.configure(admin,setup.owner().workspaceId(),new ResourceLimits(5,2,1000,200,300,1000,10000,10,3600),"Fill storage reservation");
        UUID experiment=experiments.start(setup.owner(),command(setup,1));
        var worker=workers.register(setup.principal(),registration(setup,"worker"),now);
        var grant=leases.claim(setup.principal(),new ClaimRequest(worker.workerId(),setup.agent(),"v1",0),now).orElseThrow(); leases.started(setup.principal(),grant.trialId(),grant.attemptToken(),now);
        var store=org.mockito.Mockito.mock(io.agenteval.telemetry.ArtifactStore.class);
        org.mockito.Mockito.when(store.uploadUrl(org.mockito.Mockito.anyString(),org.mockito.Mockito.anyLong())).thenReturn(java.net.URI.create("https://fixture.invalid/upload"));
        var artifacts=new io.agenteval.telemetry.ArtifactService(new io.agenteval.telemetry.JdbcArtifactRepository(jdbc,transactions,leases),store,Clock.fixed(now,ZoneOffset.UTC));
        artifacts.initialize(setup.principal(),grant.trialId(),grant.attemptToken(),new RegisterArtifactRequest("pending.txt","text/plain",999,"a".repeat(64),"pending"));
        leases.complete(setup.principal(),grant.trialId(),grant.attemptToken(),new CompletionRequest(TrialExecutionStatus.SUCCEEDED,json.createObjectNode(),0,0,0,List.of()),now);
        var evidence=new io.agenteval.telemetry.EvidenceService(new io.agenteval.telemetry.JdbcEvidenceRepository(jdbc,transactions,json),Clock.fixed(now.plusSeconds(31),ZoneOffset.UTC));
        evidence.freezeReady(100);
        var reports=new io.agenteval.reporting.ReportService(new io.agenteval.evaluation.JdbcEvaluationRepository(jdbc,transactions,json),new io.agenteval.evaluation.EvaluationAggregator());
        assertThat(reports.trials(setup.owner(),experiment,null,1).items().getFirst().evidenceWaitReason()).isEqualTo("STORAGE_LIMIT");
        assertThat(reports.get(setup.owner(),experiment).pendingAttempts()).isEqualTo(1);
        limits.configure(admin,setup.owner().workspaceId(),new ResourceLimits(5,2,16384,200,300,1000,10000,10,3600),"Allow evidence snapshot");
        evidence.freezeReady(100);
        assertThat(reports.trials(setup.owner(),experiment,null,1).items().getFirst().snapshotId()).isNotNull();
        assertThat(reports.trials(setup.owner(),experiment,null,1).items().getFirst().evidenceWaitReason()).isNull();
    }
}
