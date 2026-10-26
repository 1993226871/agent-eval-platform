package io.agenteval.experiments;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenteval.agents.*;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import io.agenteval.protocol.*;
import io.agenteval.quota.*;
import io.agenteval.telemetry.*;
import io.agenteval.workers.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class DeletionRaceIT {
    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static DataSourceTransactionManager transactions;
    final ObjectMapper json=new ObjectMapper();
    final TestClock clock=new TestClock();
    final MemoryStore store=new MemoryStore();
    IdentityService identity;
    ExperimentService experiments;
    JdbcLeaseRepository leases;
    ArtifactService artifacts;
    ExperimentDeletionService deletion;
    QuotaService quota;
    @BeforeAll static void database() {
        postgres=new PostgreSQLContainer<>("postgres:17.11"); postgres.start();
        var source=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate(); jdbc=JdbcClient.create(source); transactions=new DataSourceTransactionManager(source);
    }
    @AfterAll static void stop() { if(postgres!=null) postgres.stop(); }
    @BeforeEach void setup() {
        identity=new IdentityService(new JdbcIdentityRepository(jdbc,transactions),new BCryptPasswordEncoder(4));
        var catalog=new CatalogService(new JdbcCatalogRepository(jdbc,transactions,json)); var agents=new AgentService(new JdbcAgentRepository(jdbc,transactions));
        experiments=new ExperimentService(new JdbcExperimentRepository(jdbc,transactions,json),catalog,agents,clock);
        leases=new JdbcLeaseRepository(jdbc,transactions,json); artifacts=new ArtifactService(new JdbcArtifactRepository(jdbc,transactions,leases),store,clock);
        quota=new QuotaService(new JdbcQuotaRepository(jdbc,transactions));
        deletion=new ExperimentDeletionService(new JdbcExperimentDeletionRepository(jdbc,transactions),store,clock);
    }
    record Fixture(WorkspaceActor owner,UUID experiment,UUID trial,WorkerPrincipal principal,String token,UUID artifact) {}
    Fixture fixture() throws Exception {
        var owner=identity.createUser(UUID.randomUUID()+"@deletion.local","Temporary-Password-1",false).actor();
        var catalog=new CatalogService(new JdbcCatalogRepository(jdbc,transactions,json)); var agents=new AgentService(new JdbcAgentRepository(jdbc,transactions));
        var agent=agents.create(owner,"Orders","v1");
        var bundle=new TemplateService(catalog,List.of(
            new TemplateService.Template("order-query",ResourceKind.DATASET,"Orders",json.readTree("{\"cases\":[{\"instruction\":\"Check amount\",\"input\":{},\"expected\":100}]}")),
            new TemplateService.Template("amount-rule",ResourceKind.EVALUATOR,"Amount",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"amount\",\"threshold\":1,\"required_evidence\":[\"output\"],\"config\":{\"operator\":\"eq\",\"selector\":\"/output/amount\",\"expected\":100}}"))
        )).copyStarter(owner);
        UUID experiment=experiments.start(owner,new StartExperimentCommand(agent.id(),"v1",bundle.datasetVersion().id(),bundle.suiteVersion().id(),1,clock.instant().plusSeconds(3600),UUID.randomUUID().toString()));
        var workers=new JdbcWorkerRepository(jdbc,transactions); var credentials=new WorkerCredentialService(workers,agents);
        var issued=credentials.issue(owner,Set.of(agent.id())); var principal=credentials.authenticate(issued.secret());
        var registration=workers.register(principal,new RegisterWorkerRequest(agent.id(),"v1","instance-"+UUID.randomUUID(),"1.0","PYTHON","test",1),clock.instant());
        var grant=leases.claim(principal,new ClaimRequest(registration.workerId(),agent.id(),"v1",0),clock.instant()).orElseThrow();
        leases.started(principal,grant.trialId(),grant.attemptToken(),clock.instant());
        var upload=artifacts.initialize(principal,grant.trialId(),grant.attemptToken(),new RegisterArtifactRequest("report.txt","text/plain",3,"a".repeat(64),"artifact"));
        store.objects.add("staging/"+owner.workspaceId()+"/"+upload.artifactId());
        return new Fixture(owner,experiment,grant.trialId(),principal,grant.attemptToken(),upload.artifactId());
    }
    @Test void deletionWaitsForDurableRunnerEvidenceCleanupAndNeverScrubsAnotherWorkspace() throws Exception {
        var target=fixture(); var foreign=fixture(); UUID job=UUID.randomUUID(),other=UUID.randomUUID();
        jdbc.sql("insert into runner_executions(job_id,experiment_id,workspace_id) values(?,?,?),(?,?,?)").params(job,target.experiment(),target.owner().workspaceId(),other,foreign.experiment(),foreign.owner().workspaceId()).update();
        var unavailable=new java.util.concurrent.atomic.AtomicBoolean(true); var cleaned=new java.util.ArrayList<UUID>();
        var service=new ExperimentDeletionService(new JdbcExperimentDeletionRepository(jdbc,transactions),store,id -> { cleaned.add(id); if(unavailable.get()) throw new IllegalStateException("Fixture Runner unavailable"); },clock);
        service.deleteExperiment(target.owner(),target.experiment(),UUID.randomUUID()); clock.advance(301);
        assertThatThrownBy(() -> service.cleanup(target.experiment())).isInstanceOf(IllegalStateException.class);
        assertThat(state(target.experiment())).isEqualTo("DELETING");
        assertThat(jdbc.sql("select count(*) from trials where experiment_id=?").param(target.experiment()).query(Long.class).single()).isEqualTo(1);
        unavailable.set(false); clock.advance(31); assertThat(service.cleanup(target.experiment())).isTrue();
        assertThat(state(target.experiment())).isEqualTo("DELETED"); assertThat(cleaned).containsExactly(job,job);
        assertThat(jdbc.sql("select cleaned_at is not null from runner_executions where job_id=?").param(job).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select cleaned_at is null from runner_executions where job_id=?").param(other).query(Boolean.class).single()).isTrue();
    }
    @Test void lateUploadCannotResurrectDeletedExperiment() throws Exception {
        var target=fixture(); var foreign=fixture();
        var admin=identity.createUser(UUID.randomUUID()+"@admin.local","Temporary-Password-1",true).actor();
        quota.grant(admin,target.owner().workspaceId(),100,"Initial budget",UUID.randomUUID());
        var ledgerBeforeDeletion=quota.entries(target.owner(),100); var otherTenantObjectsBefore=store.objects.stream().filter(key -> key.contains(foreign.owner().workspaceId().toString())).sorted().toList();
        UUID key=UUID.randomUUID(); deletion.deleteExperiment(target.owner(),target.experiment(),key);
        deletion.deleteExperiment(target.owner(),target.experiment(),key);
        assertThatThrownBy(() -> deletion.deleteExperiment(foreign.owner(),target.experiment(),UUID.randomUUID())).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(() -> leases.complete(target.principal(),target.trial(),target.token(),new CompletionRequest(TrialExecutionStatus.SUCCEEDED,json.createObjectNode().put("secret","must-not-return"),0,0,0,List.of()),clock.instant())).isInstanceOf(AttemptRejectedException.class);
        assertThatThrownBy(() -> artifacts.complete(target.principal(),target.trial(),target.token(),target.artifact())).isInstanceOf(AttemptRejectedException.class);
        clock.advance(301); store.fail=true;
        assertThatThrownBy(() -> deletion.cleanup(target.experiment())).isInstanceOf(StorageUnavailableException.class);
        assertThat(state(target.experiment())).isEqualTo("DELETING");
        clock.advance(901); store.fail=false; deletion.cleanup(target.experiment());
        assertThat(state(target.experiment())).isEqualTo("DELETED");
        assertThatThrownBy(() -> experiments.get(target.owner(),target.experiment())).isInstanceOf(ScopeViolationException.class);
        assertThat(jdbc.sql("select count(*) from trials where experiment_id=?").param(target.experiment()).query(Long.class).single()).isZero();
        // A request started while a presigned URL was valid can finish late. Keep trusted tombstones for re-sweeps.
        String late="staging/"+target.owner().workspaceId()+"/"+target.artifact(); store.objects.add(late); clock.advance(3601); deletion.cleanup(target.experiment());
        assertThat(store.objects).doesNotContain(late);
        var ledgerAfterDeletion=quota.entries(target.owner(),100);
        assertThat(ledgerAfterDeletion).containsExactlyElementsOf(ledgerBeforeDeletion);
        var otherTenantObjectsAfter=store.objects.stream().filter(object -> object.contains(foreign.owner().workspaceId().toString())).sorted().toList();
        assertThat(otherTenantObjectsAfter).containsExactlyElementsOf(otherTenantObjectsBefore);
    }
    String state(UUID experiment) { return jdbc.sql("select status from experiments where id=?").param(experiment).query(String.class).single(); }
    @Test void logicalDeletionImmediatelyHidesFrozenPrivateEvidenceAndReviews() throws Exception {
        var target=fixture();
        leases.complete(target.principal(),target.trial(),target.token(),new CompletionRequest(TrialExecutionStatus.SUCCEEDED,json.createObjectNode().put("amount",100),0,0,0,List.of()),clock.instant());
        clock.advance(31);
        var evidence=new EvidenceService(new JdbcEvidenceRepository(jdbc,transactions,json),clock);
        var snapshot=evidence.freeze(target.owner(),target.trial(),false);
        var evaluation=new io.agenteval.evaluation.JdbcEvaluationRepository(jdbc,transactions,json);
        var grading=new io.agenteval.evaluation.EvaluationOrchestrator(evaluation,evidence,new io.agenteval.evaluation.RuleEvaluator(),clock);
        grading.schedule(10); assertThat(grading.runNext()).isTrue();
        UUID result=jdbc.sql("select job_id from evaluation_results where workspace_id=?").param(target.owner().workspaceId()).query(UUID.class).single();
        var reviews=new io.agenteval.evaluation.HumanReviewService(new io.agenteval.evaluation.JdbcHumanReviewRepository(jdbc,transactions),clock);
        reviews.add(target.owner(),result,java.math.BigDecimal.ONE,"Original review");
        deletion.deleteExperiment(target.owner(),target.experiment(),UUID.randomUUID());
        assertThatThrownBy(() -> new JdbcArtifactRepository(jdbc,transactions,leases).get(target.owner(),target.artifact())).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(() -> evidence.load(target.owner(),snapshot.id())).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(() -> evidence.freeze(target.owner(),target.trial(),false)).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(() -> reviews.list(target.owner(),result,10)).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(() -> new io.agenteval.reporting.ReportService(evaluation,new io.agenteval.evaluation.EvaluationAggregator()).get(target.owner(),target.experiment())).isInstanceOf(ScopeViolationException.class);
        clock.advance(301); deletion.cleanup(target.experiment());
        var usage=new io.agenteval.administration.ResourceLimitService(new io.agenteval.administration.JdbcResourceLimitRepository(jdbc,transactions)).get(target.owner()).usage();
        assertThat(usage.evidenceBytes()).isZero(); assertThat(usage.artifactBytes()).isZero();
    }
    @Test void lateModelResponseCannotRestorePrivateContentButFeesRemainReconcilable() throws Exception {
        var target=fixture();
        leases.complete(target.principal(),target.trial(),target.token(),new CompletionRequest(TrialExecutionStatus.SUCCEEDED,json.createObjectNode().put("amount",100),0,0,0,List.of()),clock.instant()); clock.advance(31);
        var evidence=new EvidenceService(new JdbcEvidenceRepository(jdbc,transactions,json),clock); evidence.freeze(target.owner(),target.trial(),false);
        var repository=new io.agenteval.evaluation.JdbcEvaluationRepository(jdbc,transactions,json);
        var grading=new io.agenteval.evaluation.EvaluationOrchestrator(repository,evidence,new io.agenteval.evaluation.RuleEvaluator(),clock); grading.schedule(10); assertThat(grading.runNext()).isTrue();
        UUID job=jdbc.sql("select job_id from evaluation_results where workspace_id=?").param(target.owner().workspaceId()).query(UUID.class).single();
        UUID profile=UUID.randomUUID(),price=UUID.randomUUID(),call=UUID.randomUUID();
        jdbc.sql("insert into model_profiles(id,name,endpoint,encrypted_api_key,model,input_token_bound,max_output_tokens,max_request_bytes,max_concurrent,bound_reference,created_by) values(?,?,'https://fixture.invalid/v1/chat/completions','unused-test-ciphertext','fixture',100,10,65536,1,'Fixture enforced bound',?)").params(profile,"Fixture",target.owner().userId()).update();
        jdbc.sql("insert into model_price_versions(id,profile_id,input_rate,output_rate,denominator) values(?,?,1,1,1)").params(price,profile).update();
        var codec=new ProtocolCodec(); var request=new io.agenteval.model.JudgeRequest(target.owner().workspaceId(),call,profile,price,"Private rubric","secret-task-body",10);
        var response=new io.agenteval.model.JudgeResponse(call,"PENDING_RECONCILIATION","private-score-content",10L,5L,null,profile,price,"fixture","stop","fixture-response","POST_SEND_UNCERTAIN","private-raw-response",15L);
        jdbc.sql("insert into model_calls(call_id,workspace_id,profile_id,price_version_id,request_hash,request_json,state,upper_credits,response_json,created_at,updated_at,evaluation_job_id) values(?,?,?,?,?,cast(? as jsonb),'PENDING_RECONCILIATION',100,cast(? as jsonb),?,?,?)")
            .params(call,target.owner().workspaceId(),profile,price,"a".repeat(64),codec.encode(request),codec.encode(response),java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant()),job).update();
        var admin=identity.createUser(UUID.randomUUID()+"@billing.local","Temporary-Password-1",true).actor();
        quota.grant(admin,target.owner().workspaceId(),100,"Budget",UUID.randomUUID()); quota.reserve(target.owner().workspaceId(),call,100); quota.markSendIntent(call); quota.markReconciliation(call);
        var before=quota.entries(target.owner(),100);
        deletion.deleteExperiment(target.owner(),target.experiment(),UUID.randomUUID()); clock.advance(301); deletion.cleanup(target.experiment());
        String stored=jdbc.sql("select request_json::text||response_json::text from model_calls where call_id=?").param(call).query(String.class).single();
        assertThat(stored).doesNotContain("secret-task-body","private-score-content","private-raw-response");
        assertThat(quota.entries(target.owner(),100)).containsExactlyElementsOf(before);
        jdbc.sql("update model_calls set response_json=response_json||'{\"content\":\"late-private-score\",\"raw_response\":\"late-private-raw\"}'::jsonb where call_id=?").param(call).update();
        assertThat(jdbc.sql("select response_json::text from model_calls where call_id=?").param(call).query(String.class).single()).doesNotContain("late-private-score","late-private-raw");
        var profiles=new io.agenteval.model.EncryptedModelProfileRepository(jdbc,transactions,new io.agenteval.model.ModelSecretBox(new byte[32]),Set.of("https://fixture.invalid"));
        var calls=new io.agenteval.model.JdbcModelCallRepository(jdbc,transactions,profiles,quota); UUID key=UUID.randomUUID();
        var resolved=calls.reconcile(admin,call,10,5,"Verified provider usage",key,clock.instant()); calls.reconcile(admin,call,10,5,"Verified provider usage",key,clock.instant());
        assertThat(resolved.credits()).isEqualTo(15); assertThat(quota.wallet(target.owner()).consumed()).isEqualTo(15); assertThat(quota.wallet(target.owner()).reserved()).isZero();
        assertThat(quota.entries(target.owner(),100).stream().filter(entry -> entry.type().equals("SETTLE"))).hasSize(1);
    }
    @Test void removesOnlyUnsharedPreviewCopiesAndRetainsSourceDrafts() throws Exception {
        var target=fixture(); var catalog=new CatalogService(new JdbcCatalogRepository(jdbc,transactions,json));
        leases.complete(target.principal(),target.trial(),target.token(),new CompletionRequest(TrialExecutionStatus.SUCCEEDED,json.createObjectNode().put("amount",100),0,0,0,List.of()),clock.instant()); clock.advance(31);
        var evidence=new EvidenceService(new JdbcEvidenceRepository(jdbc,transactions,json),clock);
        UUID draft=jdbc.sql("select id from catalog_resources where workspace_id=? and kind='EVALUATOR' and not archived").param(target.owner().workspaceId()).query(UUID.class).single();
        var source=catalog.draft(target.owner(),draft); long before=jdbc.sql("select count(*) from catalog_resources where workspace_id=?").param(target.owner().workspaceId()).query(Long.class).single();
        var reevaluate=new io.agenteval.evaluation.ReevaluationService(new io.agenteval.evaluation.JdbcReevaluationRepository(jdbc,transactions),catalog,evidence,clock);
        var previews=new io.agenteval.evaluation.EvaluatorPreviewService(new io.agenteval.evaluation.JdbcEvaluatorPreviewRepository(jdbc,transactions),catalog,reevaluate,clock);
        UUID preview=previews.create(target.owner(),draft,source.revision(),target.trial(),"preview");
        var view=new io.agenteval.evaluation.JdbcEvaluationRunRepository(jdbc,transactions).get(target.owner(),preview);
        assertThat(jdbc.sql("select count(*) from catalog_resources where workspace_id=?").param(target.owner().workspaceId()).query(Long.class).single()).isEqualTo(before+2);
        var original=experiments.get(target.owner(),target.experiment());
        UUID shared=experiments.start(target.owner(),new StartExperimentCommand(original.agentId(),"v1",original.datasetVersionId(),view.suiteVersionId(),1,clock.instant().plusSeconds(3600),"shared-preview-suite"));
        deletion.deleteExperiment(target.owner(),target.experiment(),UUID.randomUUID()); clock.advance(301); deletion.cleanup(target.experiment());
        assertThat(catalog.version(target.owner(),view.suiteVersionId()).id()).isEqualTo(view.suiteVersionId());
        assertThat(experiments.get(target.owner(),shared).status()).isEqualTo("ACTIVE");
        deletion.deleteExperiment(target.owner(),shared,UUID.randomUUID()); clock.advance(301); deletion.cleanup(shared);
        clock.advance(3601); deletion.cleanup(target.experiment());
        assertThat(jdbc.sql("select count(*) from catalog_resources where workspace_id=?").param(target.owner().workspaceId()).query(Long.class).single()).isEqualTo(before);
        assertThat(catalog.draft(target.owner(),draft).revision()).isEqualTo(source.revision());
        assertThat(catalog.draft(target.owner(),draft).archived()).isFalse();
    }
    @Test void verificationAlreadyInFlightCannotPublishAfterDeleteWins() throws Exception {
        var target=fixture(); store.verificationEntered=new java.util.concurrent.CountDownLatch(1); store.verificationContinue=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var finishing=pool.submit(() -> artifacts.complete(target.principal(),target.trial(),target.token(),target.artifact()));
            try {
                assertThat(store.verificationEntered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                deletion.deleteExperiment(target.owner(),target.experiment(),UUID.randomUUID());
            } finally { store.verificationContinue.countDown(); }
            assertThatThrownBy(() -> finishing.get(5,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(AttemptRejectedException.class);
        }
        assertThat(store.objects).contains("sealed/"+target.owner().workspaceId()+"/"+target.artifact());
        clock.advance(301); deletion.cleanup(target.experiment());
        assertThat(store.objects).doesNotContain("sealed/"+target.owner().workspaceId()+"/"+target.artifact(),"staging/"+target.owner().workspaceId()+"/"+target.artifact());
        assertThat(state(target.experiment())).isEqualTo("DELETED");
    }
    static final class MemoryStore implements ArtifactStore {
        final Set<String> objects=java.util.concurrent.ConcurrentHashMap.newKeySet(); boolean fail;
        java.util.concurrent.CountDownLatch verificationEntered,verificationContinue;
        public URI uploadUrl(String key,long size) { return URI.create("https://fixture.invalid/upload"); }
        public void verifyAndSeal(String stage,String sealed,long size,String hash) {
            if(verificationEntered!=null) {
                verificationEntered.countDown(); try { if(!verificationContinue.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Fixture verification timeout"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
            objects.add(sealed);
        }
        public URI downloadUrl(String key) { return URI.create("https://fixture.invalid/download"); }
        public void deleteObject(String key) { if(fail) throw new StorageUnavailableException(); objects.remove(key); }
    }
    static final class TestClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.now());
        void advance(long seconds) { now.updateAndGet(value -> value.plusSeconds(seconds)); }
        public Instant instant() { return now.get(); } public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; }
    }
}
