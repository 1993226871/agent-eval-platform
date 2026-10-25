package io.agenteval.telemetry;
import io.agenteval.api.PlatformApiApplication;
import io.agenteval.agents.*;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import io.agenteval.experiments.*;
import io.agenteval.protocol.*;
import io.agenteval.workers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.*;
import java.net.*;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes=PlatformApiApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceIngestionIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) { p.add("spring.datasource.url",postgres::getJdbcUrl); p.add("spring.datasource.username",postgres::getUsername); p.add("spring.datasource.password",postgres::getPassword); }
    @Autowired IdentityService identities;
    @Autowired AgentService agents;
    @Autowired TemplateService templates;
    @Autowired ExperimentService experiments;
    @Autowired WorkerCredentialService credentials;
    @Autowired WorkerRegistry registry;
    @Autowired LeaseService leases;
    @Autowired TelemetryService telemetry;
    @Autowired JdbcTemplate sql;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    final ProtocolCodec codec=new ProtocolCodec();
    record Setup(WorkspaceActor actor,WorkerPrincipal principal,IssuedCredential credential,LeaseGrant grant) {}
    Setup setup() {
        var actor=identities.createUser(UUID.randomUUID()+"@test.local","Temporary-Password-1",false).actor();
        var agent=agents.create(actor,"trace-agent","v1"); var bundle=templates.copyStarter(actor);
        experiments.start(actor,new StartExperimentCommand(agent.id(),"v1",bundle.datasetVersion().id(),bundle.suiteVersion().id(),1,Instant.now().plusSeconds(3600),UUID.randomUUID().toString()));
        var credential=credentials.issue(actor,Set.of(agent.id())); var principal=credentials.authenticate(credential.secret());
        var worker=registry.register(principal,new RegisterWorkerRequest(agent.id(),"v1","trace-worker","1.0","JAVA","0.1.0",1));
        var grant=leases.claim(principal,new ClaimRequest(worker.workerId(),agent.id(),"v1",0)).orElseThrow();
        leases.started(principal,grant.trialId(),grant.attemptToken()); return new Setup(actor,principal,credential,grant);
    }
    @Test void acceptsOutOfOrderAndRejectsConflictingReplay() throws Exception {
        var s=setup(); var root=event(s,1,"1111111111111111",null,EventType.SPAN_STARTED,"agent", "{\"input\":{}}");
        var start=event(s,2,"2222222222222222","1111111111111111",EventType.SPAN_STARTED,"query_order","{\"input\":{\"order_id\":\"A\"}}");
        var end=event(s,3,"2222222222222222","1111111111111111",EventType.SPAN_COMPLETED,"query_order","{\"output\":{\"amount\":100},\"duration_ms\":12}");
        var done=event(s,4,"1111111111111111",null,EventType.SPAN_COMPLETED,"agent","{\"output\":{\"amount\":100},\"duration_ms\":20}");
        assertThat(telemetry.ingest(s.principal(),s.grant().trialId(),s.grant().attemptToken(),List.of(end,root,start,done)).acknowledgedEventIds()).hasSize(4);
        telemetry.ingest(s.principal(),s.grant().trialId(),s.grant().attemptToken(),List.of(end));
        assertThat(sql.queryForObject("select count(*) from trace_events where trial_id=?",Integer.class,s.grant().trialId())).isEqualTo(4);
        var spans=telemetry.queryTrialTrace(s.actor(),s.grant().trialId(),null,100).spans();
        assertThat(spans).hasSize(2);
        var tool=spans.stream().filter(span -> span.spanId().equals("2222222222222222")).findFirst().orElseThrow();
        assertThat(tool.output().get("amount").asInt()).isEqualTo(100);
        assertThat(tool.parentSpanId()).isEqualTo("1111111111111111");
        assertThat(tool.origin()).isEqualTo("worker_reported");
        var conflict=new TraceEvent(end.schemaVersion(),end.eventId(),end.trialId(),end.traceId(),end.spanId(),end.parentSpanId(),end.eventType(),end.spanKind(),end.name(),end.sequence(),end.occurredAt(),codec.tree("{\"output\":{\"amount\":999}}"),end.capture());
        assertThatThrownBy(() -> telemetry.ingest(s.principal(),s.grant().trialId(),s.grant().attemptToken(),List.of(conflict))).isInstanceOf(TelemetryConflictException.class);
        assertThat(telemetry.queryTrialTrace(s.actor(),s.grant().trialId(),null,100).spans().stream().filter(span -> span.spanId().equals(end.spanId())).findFirst().orElseThrow().output().get("amount").asInt()).isEqualTo(100);
    }
    @Test void rejectsOversizeBatchesPayloadsAndWrongOwnership() throws Exception {
        var s=setup(); var records=new ArrayList<TraceEvent>();
        for(int i=1;i<=101;i++) records.add(event(s,i,"1111111111111111",null,EventType.LOG,"log","{}"));
        assertThatThrownBy(() -> telemetry.ingest(s.principal(),s.grant().trialId(),s.grant().attemptToken(),records)).isInstanceOf(TelemetryLimitException.class);
        var huge=event(s,1,"1111111111111111",null,EventType.LOG,"log","{\"text\":\""+"x".repeat(65537)+"\"}");
        assertThatThrownBy(() -> telemetry.ingest(s.principal(),s.grant().trialId(),s.grant().attemptToken(),List.of(huge))).isInstanceOf(TelemetryLimitException.class);
        var other=setup();
        assertThatThrownBy(() -> telemetry.ingest(other.principal(),s.grant().trialId(),s.grant().attemptToken(),List.of(records.getFirst()))).isInstanceOf(AttemptRejectedException.class);
        assertThat(sql.queryForObject("select count(*) from trace_events where trial_id=?",Integer.class,s.grant().trialId())).isZero();
    }
    @Test void httpRejectsSpoofedOriginAndLargeBodiesBeforeIngestion() throws Exception {
        var s=setup(); var item=event(s,1,"1111111111111111",null,EventType.SPAN_STARTED,"agent","{}");
        String spoof="{\"events\":["+codec.encode(item).replace("\"name\":","\"source\":\"verifier_observed\",\"name\":")+"]}";
        assertThat(post(s,spoof).statusCode()).isEqualTo(400);
        assertThat(post(s," ".repeat(1024*1024+1)).statusCode()).isEqualTo(413);
    }
    @Test void tracePaginationIsBoundToItsWorkspaceAndTrial() throws Exception {
        var s=setup();
        telemetry.ingest(s.principal(),s.grant().trialId(),s.grant().attemptToken(),List.of(event(s,1,"1111111111111111",null,EventType.SPAN_STARTED,"agent","{}"),event(s,2,"2222222222222222","1111111111111111",EventType.SPAN_STARTED,"tool","{}")));
        var first=telemetry.queryTrialTrace(s.actor(),s.grant().trialId(),null,1); assertThat(first.spans()).hasSize(1); assertThat(first.nextCursor()).isNotNull();
        var second=telemetry.queryTrialTrace(s.actor(),s.grant().trialId(),first.nextCursor(),1); assertThat(second.spans()).hasSize(1);
        assertThat(second.spans().getFirst().spanId()).isNotEqualTo(first.spans().getFirst().spanId());
        var other=setup();
        assertThatThrownBy(() -> telemetry.queryTrialTrace(other.actor(),s.grant().trialId(),first.nextCursor(),1)).isInstanceOf(ScopeViolationException.class);
    }
    @Test void lateEvidenceUsesTerminalGraceWithoutChangingExecutionOutcome() throws Exception {
        var s=setup();
        leases.complete(s.principal(),s.grant().trialId(),s.grant().attemptToken(),new CompletionRequest(TrialExecutionStatus.SUCCEEDED,json.readTree("{\"amount\":100}"),2,2,0,List.of()));
        sql.update("update trial_leases set expires_at=? where trial_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(5)),s.grant().trialId());
        var late=event(s,2,"1111111111111111",null,EventType.SPAN_COMPLETED,"agent","{\"output\":{\"amount\":100}}");
        assertThat(telemetry.ingest(s.principal(),s.grant().trialId(),s.grant().attemptToken(),List.of(late)).acknowledgedEventIds()).containsExactly(late.eventId());
        assertThat(sql.queryForObject("select status from trials where id=?",String.class,s.grant().trialId())).isEqualTo("SUCCEEDED");
        sql.update("update trials set completed_at=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(31)),s.grant().trialId());
        var tooLate=event(s,1,"1111111111111111",null,EventType.SPAN_STARTED,"agent","{}");
        assertThatThrownBy(() -> telemetry.ingest(s.principal(),s.grant().trialId(),s.grant().attemptToken(),List.of(tooLate))).isInstanceOf(AttemptRejectedException.class);
    }
    TraceEvent event(Setup s,long sequence,String span,String parent,EventType type,String name,String payload) throws Exception {
        return new TraceEvent("1.0",UUID.randomUUID(),s.grant().trialId(),s.grant().traceId(),span,parent,type,parent==null?SpanKind.AGENT:SpanKind.TOOL,name,sequence,Instant.now(),codec.tree(payload),new CaptureMetadata(false,false));
    }
    HttpResponse<String> post(Setup s,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/trials/"+s.grant().trialId()+"/events:batch"))
            .header("Authorization","Bearer "+s.credential().secret()).header("X-Attempt-Token",s.grant().attemptToken()).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try(var client=HttpClient.newHttpClient()) { return client.send(request,HttpResponse.BodyHandlers.ofString()); }
    }
}
