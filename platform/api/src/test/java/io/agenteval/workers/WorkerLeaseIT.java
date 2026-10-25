package io.agenteval.workers;
import io.agenteval.api.PlatformApiApplication;
import io.agenteval.agents.*;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import io.agenteval.experiments.*;
import io.agenteval.protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.net.*;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes={PlatformApiApplication.class,WorkerLeaseIT.TimeConfiguration.class},webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerLeaseIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) { p.add("spring.datasource.url",postgres::getJdbcUrl); p.add("spring.datasource.username",postgres::getUsername); p.add("spring.datasource.password",postgres::getPassword); }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-25T00:00:00Z"));
        void advance(long seconds) { now.updateAndGet(time -> time.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        @Override public Instant instant() { return now.get(); }
    }
    @TestConfiguration static class TimeConfiguration { @Bean @Primary MutableClock testClock() { return new MutableClock(); } }
    @Autowired MutableClock clock;
    @Autowired IdentityService identities;
    @Autowired AgentService agents;
    @Autowired TemplateService templates;
    @Autowired CatalogService catalog;
    @Autowired ExperimentService experiments;
    @Autowired WorkerCredentialService credentials;
    @Autowired WorkerRegistry registry;
    @Autowired LeaseService leases;
    @Autowired LeaseSweeper sweeper;
    @Autowired ObjectMapper json;
    @Autowired javax.sql.DataSource dataSource;
    @LocalServerPort int port;
    @BeforeEach void advancePastOldLeases() { clock.advance(3600); }

    record Setup(WorkspaceActor actor,AgentRecord agent,UUID experimentId,IssuedCredential credential,WorkerPrincipal principal) {}
    Setup setup() {
        var actor=identities.createUser(UUID.randomUUID()+"@test.local","Temporary-Password-1",false).actor();
        var agent=agents.create(actor,"example","v1"); var bundle=templates.copyStarter(actor);
        var dataset=catalog.draft(actor,bundle.datasetVersion().resourceId()); var content=dataset.content();
        ((com.fasterxml.jackson.databind.node.ObjectNode)content.at("/cases/0")).put("timeout_seconds",300);
        catalog.update(actor,dataset.id(),dataset.revision(),dataset.name(),content);
        var datasetVersion=catalog.publish(actor,dataset.id());
        var experiment=experiments.start(actor,new StartExperimentCommand(agent.id(),"v1",datasetVersion.id(),bundle.suiteVersion().id(),1,clock.instant().plusSeconds(3600),UUID.randomUUID().toString()));
        var credential=credentials.issue(actor,Set.of(agent.id()));
        return new Setup(actor,agent,experiment,credential,credentials.authenticate(credential.secret()));
    }
    WorkerRegistration register(Setup setup,String instance) { return registry.register(setup.principal(),new RegisterWorkerRequest(setup.agent().id(),"v1",instance,"1.0","JAVA","0.1.0",1)); }
    ClaimRequest claim(Setup setup,WorkerRegistration worker) { return new ClaimRequest(worker.workerId(),setup.agent().id(),"v1",0); }

    @Test void fencesConcurrentClaimsAndDoesNotReplayUnknownExecution() throws Exception {
        var s=setup(); var one=register(s,"one"); var two=register(s,"two");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=executor.invokeAll(List.of(() -> leases.claim(s.principal(),claim(s,one)),() -> leases.claim(s.principal(),claim(s,two))));
            var grants=new ArrayList<LeaseGrant>();
            for(var future:futures) { @SuppressWarnings("unchecked") var grant=(Optional<LeaseGrant>)future.get(); grant.ifPresent(grants::add); }
            assertThat(grants).hasSize(1);
            var grant=grants.getFirst();
            assertThat(json.valueToTree(grant.task()).has("expected")).isFalse();
            assertThat(grant.leaseExpiresAt()).isEqualTo(clock.instant().plusSeconds(60));
            clock.advance(61);
            assertThat(leases.claim(s.principal(),claim(s,two))).isEmpty();
            assertThat(experiments.trials(s.actor(),s.experimentId()).getFirst().status()).isEqualTo(TrialExecutionStatus.EXECUTION_UNKNOWN);
            assertThatThrownBy(() -> leases.complete(s.principal(),grant.trialId(),grant.attemptToken(),success())).isInstanceOf(AttemptRejectedException.class);
            assertThat(post(s.credential().secret(),"/api/v1/trials/"+grant.trialId()+"/complete",json.writeValueAsString(success()),grant.attemptToken()).statusCode()).isEqualTo(409);
        }
    }
    @Test void duplicateStartedAndCompletionAreIdempotent() throws Exception {
        var s=setup(); var worker=register(s,"normal"); var grant=leases.claim(s.principal(),claim(s,worker)).orElseThrow();
        leases.started(s.principal(),grant.trialId(),grant.attemptToken()); leases.started(s.principal(),grant.trialId(),grant.attemptToken());
        clock.advance(15);
        assertThat(leases.heartbeat(s.principal(),grant.trialId(),grant.attemptToken()).leaseExpiresAt()).isEqualTo(clock.instant().plusSeconds(60));
        leases.complete(s.principal(),grant.trialId(),grant.attemptToken(),success()); leases.complete(s.principal(),grant.trialId(),grant.attemptToken(),success());
        assertThat(experiments.trials(s.actor(),s.experimentId()).getFirst().status()).isEqualTo(TrialExecutionStatus.SUCCEEDED);
        assertThat(leases.claim(s.principal(),claim(s,worker))).isEmpty();
    }
    @Test void revocationAndAgentScopesAreEnforced() throws Exception {
        var s=setup(); var other=agents.create(s.actor(),"other","v1");
        assertThatThrownBy(() -> registry.register(s.principal(),new RegisterWorkerRequest(other.id(),"v1","bad","1.0","JAVA","0.1.0",1))).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(() -> registry.register(s.principal(),new RegisterWorkerRequest(s.agent().id(),"v1","old","2.0","JAVA","0.1.0",1))).isInstanceOf(IllegalArgumentException.class);
        credentials.revoke(s.actor(),s.credential().id());
        assertThatThrownBy(() -> credentials.authenticate(s.credential().secret())).isInstanceOf(InvalidWorkerCredentialException.class);
        assertThat(post(s.credential().secret(),"/api/v1/workers/register",json.writeValueAsString(new RegisterWorkerRequest(s.agent().id(),"v1","revoked","1.0","JAVA","0.1.0",1)),null).statusCode()).isEqualTo(401);
    }
    @Test void workspaceCapacityIsSharedAcrossWorkers() {
        var s=setup(); var one=register(s,"cap1"); var two=register(s,"cap2"); var three=register(s,"cap3");
        var bundle=templates.copyStarter(s.actor());
        for(int i=0;i<2;i++) experiments.start(s.actor(),new StartExperimentCommand(s.agent().id(),"v1",bundle.datasetVersion().id(),bundle.suiteVersion().id(),1,clock.instant().plusSeconds(3600),UUID.randomUUID().toString()));
        assertThat(leases.claim(s.principal(),claim(s,one))).isPresent();
        assertThat(leases.claim(s.principal(),claim(s,two))).isPresent();
        assertThat(leases.claim(s.principal(),claim(s,three))).isEmpty();
    }
    @Test void cancellationCannotBeOverwrittenByLateSuccess() throws Exception {
        var s=setup(); var worker=register(s,"cancel"); var grant=leases.claim(s.principal(),claim(s,worker)).orElseThrow();
        leases.started(s.principal(),grant.trialId(),grant.attemptToken()); experiments.cancel(s.actor(),s.experimentId());
        assertThat(leases.heartbeat(s.principal(),grant.trialId(),grant.attemptToken()).cancelRequested()).isTrue();
        leases.complete(s.principal(),grant.trialId(),grant.attemptToken(),success());
        assertThat(experiments.trials(s.actor(),s.experimentId()).getFirst().status()).isEqualTo(TrialExecutionStatus.CANCELLED);
    }
    @Test void longPollDeliversTaskCreatedWhileWaiting() throws Exception {
        var s=setup(); var worker=register(s,"poll");
        var first=leases.claim(s.principal(),claim(s,worker)).orElseThrow();
        leases.started(s.principal(),first.trialId(),first.attemptToken()); leases.complete(s.principal(),first.trialId(),first.attemptToken(),success());
        // Warm up the HTTP path, then observe a genuinely pending empty-queue claim.
        var registration=new RegisterWorkerRequest(s.agent().id(),"v1","poll","1.0","JAVA","0.1.0",1);
        assertThat(post(s.credential().secret(),"/api/v1/workers/register",json.writeValueAsString(registration),null).statusCode()).isEqualTo(200);
        var bundle=templates.copyStarter(s.actor());
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/workers/"+worker.workerId()+"/claim"))
            .header("Authorization","Bearer "+s.credential().secret()).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(new ClaimRequest(worker.workerId(),s.agent().id(),"v1",2)))).build();
        try(var client=HttpClient.newHttpClient()) {
            var response=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
            assertThatThrownBy(() -> response.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            var experiment=experiments.start(s.actor(),new StartExperimentCommand(s.agent().id(),"v1",bundle.datasetVersion().id(),bundle.suiteVersion().id(),1,clock.instant().plusSeconds(3600),UUID.randomUUID().toString()));
            var delivered=response.get(3,TimeUnit.SECONDS);
            assertThat(delivered.statusCode()).isEqualTo(200);
            assertThat(json.readTree(delivered.body()).at("/task/trial_id").asText()).isEqualTo(experiments.trials(s.actor(),experiment).getFirst().id().toString());
        }
    }
    @Test void completionBeforeStartedCannotSkipExecutionStateMachine() throws Exception {
        var s=setup(); var worker=register(s,"not-started"); var grant=leases.claim(s.principal(),claim(s,worker)).orElseThrow();
        assertThatThrownBy(() -> leases.complete(s.principal(),grant.trialId(),grant.attemptToken(),success())).isInstanceOf(AttemptRejectedException.class);
        assertThat(experiments.trials(s.actor(),s.experimentId()).getFirst().status()).isEqualTo(TrialExecutionStatus.LEASED);
    }
    @Test void backgroundSweepReclaimsLostLeaseWithoutAnyNewClaim() {
        var s=setup(); var worker=register(s,"lost"); var grant=leases.claim(s.principal(),claim(s,worker)).orElseThrow();
        clock.advance(61); assertThat(sweeper.sweep(100)).isGreaterThanOrEqualTo(1);
        assertThat(experiments.trials(s.actor(),s.experimentId()).getFirst().status()).isEqualTo(TrialExecutionStatus.EXECUTION_UNKNOWN);
        assertThatThrownBy(() -> leases.started(s.principal(),grant.trialId(),grant.attemptToken())).isInstanceOf(AttemptRejectedException.class);
    }
    @Test void heartbeatCannotExtendTaskDeadlineAndSweepMarksTimeout() {
        var s=setup(); var worker=register(s,"deadline"); var grant=leases.claim(s.principal(),claim(s,worker)).orElseThrow();
        leases.started(s.principal(),grant.trialId(),grant.attemptToken());
        HeartbeatReply last=null;
        for(int i=0;i<19;i++) { clock.advance(15); last=leases.heartbeat(s.principal(),grant.trialId(),grant.attemptToken()); }
        assertThat(last.leaseExpiresAt()).isEqualTo(grant.task().deadline());
        clock.advance(16); sweeper.sweep(100);
        assertThat(experiments.trials(s.actor(),s.experimentId()).getFirst().status()).isEqualTo(TrialExecutionStatus.TIMED_OUT);
    }
    @Test void cancellationAndCompletionUseSameSerializationBoundary() throws Exception {
        var s=setup(); var worker=register(s,"race"); var grant=leases.claim(s.principal(),claim(s,worker)).orElseThrow();
        leases.started(s.principal(),grant.trialId(),grant.attemptToken());
        try(var connection=dataSource.getConnection(); var executor=Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement(); var result=statement.executeQuery("select id from scheduler_guard where id=1 for update")) { assertThat(result.next()).isTrue(); }
            var completed=executor.submit(() -> { leases.complete(s.principal(),grant.trialId(),grant.attemptToken(),success()); return true; });
            var cancelled=executor.submit(() -> { experiments.cancel(s.actor(),s.experimentId()); return true; });
            try {
                assertThatThrownBy(() -> completed.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThatThrownBy(() -> cancelled.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(experiments.trials(s.actor(),s.experimentId()).getFirst().status()).isEqualTo(TrialExecutionStatus.RUNNING);
            } finally { connection.commit(); }
            assertThat(completed.get(5,TimeUnit.SECONDS)).isTrue(); assertThat(cancelled.get(5,TimeUnit.SECONDS)).isTrue();
            assertThat(experiments.get(s.actor(),s.experimentId()).status()).isEqualTo("CANCELLED");
        }
    }
    CompletionRequest success() throws Exception { return new CompletionRequest(TrialExecutionStatus.SUCCEEDED,json.readTree("{\"amount\":100}"),0,0,0,List.of()); }
    HttpResponse<String> post(String secret,String path,String body,String attempt) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+secret).header("Content-Type","application/json");
        if(attempt!=null) builder.header("X-Attempt-Token",attempt);
        return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
