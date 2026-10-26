package io.agenteval.telemetry;
import io.agenteval.agents.*;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import io.agenteval.experiments.*;
import io.agenteval.protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.*;

class EvidenceSnapshotIT {
    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static JdbcTemplate sql;
    static DataSourceTransactionManager tx;
    static ObjectMapper json=new ObjectMapper();
    final ProtocolCodec codec=new ProtocolCodec();
    final Instant now=Instant.parse("2026-10-26T00:00:00Z");
    WorkspaceActor actor;
    UUID trial;
    String trace;
    EvidenceService evidence;
    @BeforeAll static void database() {
        postgres=new PostgreSQLContainer<>("postgres:17.11"); postgres.start();
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate(); jdbc=JdbcClient.create(ds); sql=new JdbcTemplate(ds); tx=new DataSourceTransactionManager(ds);
    }
    @AfterAll static void stop() { if(postgres!=null) postgres.stop(); }
    @BeforeEach void setup() throws Exception {
        actor=new IdentityService(new JdbcIdentityRepository(jdbc,tx),new BCryptPasswordEncoder(4)).createUser(UUID.randomUUID()+"@test.local","Temporary-Password-1",false).actor();
        var catalog=new CatalogService(new JdbcCatalogRepository(jdbc,tx,json));
        var agents=new AgentService(new JdbcAgentRepository(jdbc,tx)); var agent=agents.create(actor,"test","v1");
        var ds=catalog.create(actor,ResourceKind.DATASET,"cases",json.readTree("{\"cases\":[{\"instruction\":\"answer\",\"input\":{},\"expected\":42}]}"));
        var ev=catalog.create(actor,ResourceKind.EVALUATOR,"rule",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"answer\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{}}"));
        var version=catalog.publish(actor,ev.id());
        var suite=catalog.create(actor,ResourceKind.SUITE,"suite",json.readTree("{\"entries\":[{\"evaluator_version\":\""+version.id()+"\",\"dimension\":\"outcome\",\"required_to_pass\":true,\"weight\":1}]}"));
        var experiments=new ExperimentService(new JdbcExperimentRepository(jdbc,tx,json),catalog,agents,Clock.fixed(now,ZoneOffset.UTC));
        var id=experiments.start(actor,new StartExperimentCommand(agent.id(),"v1",catalog.publish(actor,ds.id()).id(),catalog.publish(actor,suite.id()).id(),1,now.plusSeconds(3600),UUID.randomUUID().toString()));
        trial=experiments.trials(actor,id).getFirst().id(); trace=sql.queryForObject("select trace_id from trials where id=?",String.class,trial);
        sql.update("update trials set status='SUCCEEDED',completed_at=?,completion=cast(? as jsonb) where id=?",java.sql.Timestamp.from(now.minusSeconds(31)),"{\"status\":\"SUCCEEDED\",\"output\":42,\"final_sequence\":3,\"event_count\":3,\"dropped_count\":0,\"artifact_ids\":[]}",trial);
        evidence=new EvidenceService(new JdbcEvidenceRepository(jdbc,tx,json),Clock.fixed(now,ZoneOffset.UTC));
    }
    @Test void freezesAfterGraceAndPreservesOldSnapshot() throws Exception {
        event(1,EventType.SPAN_STARTED); event(3,EventType.SPAN_COMPLETED);
        var first=evidence.freeze(actor,trial,false);
        assertThat(first.completeness()).isEqualTo(EvidenceCompleteness.PARTIAL);
        assertThat(first.manifest().path("issues").toString()).contains("sequence_gap");
        event(2,EventType.LOG);
        assertThat(evidence.freeze(actor,trial,false).id()).isEqualTo(first.id());
        assertThat(evidence.load(actor,first.id()).contentHash()).isEqualTo(first.contentHash());
        var updated=evidence.freeze(actor,trial,true);
        assertThat(updated.contentHash()).isNotEqualTo(first.contentHash());
        assertThat(updated.completeness()).isEqualTo(EvidenceCompleteness.COMPLETE);
        assertThat(evidence.freeze(actor,trial,true).id()).isEqualTo(updated.id());
        assertThat(evidence.freeze(actor,trial,false).id()).isEqualTo(first.id());
        assertThat(evidence.context(actor,first.id()).events()).hasSize(2);
        assertThat(evidence.context(actor,updated.id()).expected().asInt()).isEqualTo(42);
        assertThat(sql.queryForObject("select count(*) from grading_jobs where trial_id=?",Integer.class,trial)).isEqualTo(2);
    }
    @Test void waitsForGraceRejectsForeignScopeAndDoesNotGradeCancelledTrials() throws Exception {
        sql.update("update trials set completed_at=? where id=?",java.sql.Timestamp.from(now),trial);
        assertThatThrownBy(() -> evidence.freeze(actor,trial,false)).isInstanceOf(EvidencePendingException.class);
        var other=new WorkspaceActor(UUID.randomUUID(),UUID.randomUUID(),false);
        assertThatThrownBy(() -> evidence.freeze(other,trial,false)).isInstanceOf(ScopeViolationException.class);
        sql.update("update trials set status='CANCELLED',completed_at=? where id=?",java.sql.Timestamp.from(now.minusSeconds(31)),trial);
        var snapshot=evidence.freeze(actor,trial,false);
        assertThat(snapshot.completeness()).isEqualTo(EvidenceCompleteness.NONE);
        assertThatThrownBy(() -> evidence.load(other,snapshot.id())).isInstanceOf(ScopeViolationException.class);
        assertThat(sql.queryForObject("select count(*) from grading_jobs where trial_id=?",Integer.class,trial)).isZero();
    }
    @Test void backgroundScanFreezesPersistedTerminalsWithoutNewWorkerRequests() {
        assertThat(evidence.freezeReady(200)).isGreaterThanOrEqualTo(1);
        assertThat(sql.queryForObject("select count(*) from evidence_snapshots where trial_id=?",Integer.class,trial)).isEqualTo(1);
    }
    @Test void runnerDownloadIsBoundToFrozenMembershipTrialAndExactMetadata() {
        UUID id=UUID.randomUUID();
        sql.update("insert into artifacts(id,workspace_id,trial_id,name,media_type,size_bytes,sha256,staging_key,object_key,idempotency_key,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",id,actor.workspaceId(),trial,"result.txt","text/plain",3,"a".repeat(64),"staging/"+id,"sealed/"+id,"test-artifact-key",java.sql.Timestamp.from(now));
        sql.update("update trials set completion=jsonb_set(completion,'{artifact_ids}',cast(? as jsonb)) where id=?","[\""+id+"\"]",trial);
        var first=evidence.freeze(actor,trial,false);
        sql.update("update artifacts set status='AVAILABLE',verified_at=? where id=?",java.sql.Timestamp.from(now),id);
        var late=evidence.freeze(actor,trial,true);
        var store=mock(ArtifactStore.class); when(store.downloadUrl("sealed/"+id)).thenReturn(java.net.URI.create("https://storage.test/artifact"));
        var artifacts=new ArtifactService(new JdbcArtifactRepository(jdbc,tx,mock(LeaseRepository.class)),store,Clock.fixed(now,ZoneOffset.UTC));
        var reference=new ArtifactRef(id,"result.txt","text/plain",3,"a".repeat(64));
        assertThatThrownBy(()->artifacts.downloadEvidence(actor,trial,first.id(),reference)).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(()->artifacts.downloadEvidence(actor,UUID.randomUUID(),late.id(),reference)).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(()->artifacts.downloadEvidence(actor,trial,late.id(),new ArtifactRef(id,"result.txt","text/plain",4,"a".repeat(64)))).isInstanceOf(ScopeViolationException.class);
        assertThat(artifacts.downloadEvidence(actor,trial,late.id(),reference)).isEqualTo(java.net.URI.create("https://storage.test/artifact"));
        verify(store,times(1)).downloadUrl(anyString());
    }
    @Test void queuedExpiryProducesFreezableTerminalWithoutWorkerCompletion() {
        sql.update("update trials set status='QUEUED',completion=null,completed_at=null where id=?",trial);
        sql.update("update experiments set deadline=? where id=(select experiment_id from trials where id=?)",java.sql.Timestamp.from(now.minusSeconds(60)),trial);
        new QueuedTrialExpiry(jdbc,Clock.fixed(now.minusSeconds(31),ZoneOffset.UTC)).expire(200);
        assertThat(evidence.freezeReady(200)).isGreaterThanOrEqualTo(1);
        assertThat(sql.queryForObject("select count(*) from evidence_snapshots where trial_id=?",Integer.class,trial)).isEqualTo(1);
    }
    @Test void absentArtifactIsRecordedAsMissingAndHugeSequencesDoNotAllocateHugeLists() throws Exception {
        var artifact=UUID.randomUUID();
        sql.update("update trials set completion=cast(? as jsonb) where id=?","{\"status\":\"SUCCEEDED\",\"output\":42,\"final_sequence\":9223372036854775807,\"event_count\":1,\"dropped_count\":1,\"artifact_ids\":[\""+artifact+"\"]}",trial);
        event(1,EventType.SPAN_STARTED);
        var snapshot=evidence.freeze(actor,trial,false);
        assertThat(snapshot.manifest().path("issues").toString()).contains("missing_artifact","sequence_gap","dropped_events");
        assertThat(evidence.context(actor,snapshot.id()).artifacts()).isEmpty();
    }
    @Test void reservesArtifactQuotaAndPublishesOnlyAfterSuccessfulVerification() throws Exception {
        var principal=new io.agenteval.workers.WorkerPrincipal(actor.workspaceId(),Set.of(),UUID.randomUUID());
        var attempts=mock(LeaseRepository.class);
        var repository=new JdbcArtifactRepository(jdbc,tx,attempts);
        var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
        ArtifactStore store=new ArtifactStore() {
            public java.net.URI uploadUrl(String key,long size) { return java.net.URI.create("https://storage.test/"+key); }
            public java.net.URI downloadUrl(String key) { return java.net.URI.create("https://storage.test/"+key); }
            public void verifyAndSeal(String stage,String sealed,long size,String hash) { if(fail.get()) throw new IllegalStateException("storage unavailable"); }
        };
        var service=new ArtifactService(repository,store,Clock.fixed(now,ZoneOffset.UTC));
        var request=new RegisterArtifactRequest("report.json","application/json",70L*1024*1024,"a".repeat(64),"report-one");
        var upload=service.initialize(principal,trial,"token",request);
        assertThat(service.initialize(principal,trial,"token",request).artifactId()).isEqualTo(upload.artifactId());
        assertThatThrownBy(() -> service.initialize(principal,trial,"token",new RegisterArtifactRequest("second","application/json",40L*1024*1024,"b".repeat(64),"report-two"))).isInstanceOf(TelemetryLimitException.class);
        assertThatThrownBy(() -> service.complete(principal,trial,"token",upload.artifactId())).isInstanceOf(IllegalStateException.class);
        assertThat(sql.queryForObject("select status from artifacts where id=?",String.class,upload.artifactId())).isEqualTo("PENDING");
        assertThatThrownBy(() -> service.download(new WorkspaceActor(UUID.randomUUID(),UUID.randomUUID(),false),upload.artifactId())).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(() -> service.download(actor,upload.artifactId())).isInstanceOf(EvidencePendingException.class);
        fail.set(false); service.complete(principal,trial,"token",upload.artifactId());
        assertThat(service.download(actor,upload.artifactId()).toString()).contains("sealed/");
        assertThat(sql.queryForObject("select status from artifacts where id=?",String.class,upload.artifactId())).isEqualTo("AVAILABLE");
        sql.update("update trials set completion=cast(? as jsonb) where id=?","{\"status\":\"SUCCEEDED\",\"output\":42,\"final_sequence\":0,\"event_count\":0,\"dropped_count\":0,\"artifact_ids\":[\""+upload.artifactId()+"\"]}",trial);
        var snapshot=evidence.freeze(actor,trial,false);
        assertThat(evidence.context(actor,snapshot.id()).artifacts()).hasSize(1);
        assertThat(snapshot.manifest().path("missing_artifacts")).isEmpty();
    }
    void event(long sequence,EventType type) throws Exception {
        var event=new TraceEvent("1.0",UUID.randomUUID(),trial,trace,"1111111111111111",null,type,SpanKind.AGENT,"agent",sequence,now.minusSeconds(40-sequence),codec.tree("{}"),new CaptureMetadata(false,false));
        var node=codec.tree(codec.encode(event));
        sql.update("insert into trace_events(workspace_id,trial_id,event_id,sequence,event_json,content_hash,received_at) values (?,?,?,?,cast(? as jsonb),?,?)",actor.workspaceId(),trial,event.eventId(),sequence,node.toString(),CanonicalJson.hash(node),java.sql.Timestamp.from(now));
    }
}
