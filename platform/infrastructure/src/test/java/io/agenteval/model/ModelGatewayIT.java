package io.agenteval.model;

import com.sun.net.httpserver.HttpServer;
import io.agenteval.identity.*;
import io.agenteval.quota.*;
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

class ModelGatewayIT {
    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static DataSourceTransactionManager tx;
    HttpServer provider;
    String origin, reply, capturedAuthorization, capturedBody;
    final String providerKey="test-provider-key-never-persist-plaintext";
    final AtomicInteger requests=new AtomicInteger();
    boolean disconnect;
    WorkspaceActor user,admin;
    QuotaService quota;
    EncryptedModelProfileRepository profiles;
    JdbcModelCallRepository calls;
    OpenAiCompatibleJudgeClient client;
    ModelGateway gateway;
    Instant now=Instant.parse("2026-10-26T00:00:00Z");
    @BeforeAll static void database() {
        postgres=new PostgreSQLContainer<>("postgres:17.11"); postgres.start();
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate(); jdbc=JdbcClient.create(ds); tx=new DataSourceTransactionManager(ds);
    }
    @AfterAll static void stopDatabase() { if(postgres!=null) postgres.stop(); }
    WorkspaceActor account(boolean admin) { return new IdentityService(new JdbcIdentityRepository(jdbc,tx),new BCryptPasswordEncoder(4)).createUser(UUID.randomUUID()+"@model.local","Temporary-Password-1",admin).actor(); }
    @BeforeEach void setup() throws Exception {
        user=account(false); admin=account(true); quota=new QuotaService(new JdbcQuotaRepository(jdbc,tx));
        provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/v1/chat/completions",exchange -> {
            requests.incrementAndGet(); capturedAuthorization=exchange.getRequestHeaders().getFirst("Authorization");
            capturedBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if(disconnect) { exchange.close(); return; }
            byte[] body=reply.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        }); provider.start(); origin="http://127.0.0.1:"+provider.getAddress().getPort();
        profiles=new EncryptedModelProfileRepository(jdbc,tx,new ModelSecretBox(new byte[32]),Set.of(origin));
        calls=new JdbcModelCallRepository(jdbc,tx,profiles,quota);
        client=new OpenAiCompatibleJudgeClient(profiles); gateway=new ModelGateway(calls,client,Clock.fixed(now,ZoneOffset.UTC));
        reply=response("not JSON",10,5);
    }
    @AfterEach void stop() {
        // Isolate shared global slot accounting between test fixtures, without deleting audit rows.
        for(var call:gateway.pending(admin,100)) if(call.workspaceId().equals(user.workspaceId())) gateway.reconcile(admin,call.callId(),0,0,"Fixture provider confirms no remaining charge",UUID.randomUUID());
        client.close(); provider.stop(0);
    }
    ModelProfile profile() {
        return new ModelProfileService(profiles).create(admin,new ModelProfileCommand("Test",origin+"/v1/chat/completions",providerKey,"test-model-v1",1000,100,65536,2,"Fixture provider enforces input token limit",1,1,1));
    }
    JudgeRequest request(ModelProfile profile) { return new JudgeRequest(user.workspaceId(),UUID.randomUUID(),profile.id(),profile.price().id(),"Judge using the fixed rubric","Untrusted evidence",20); }
    String response(String content,long input,long output) {
        return "{\"id\":\"provider-call\",\"model\":\"test-model-v1\",\"choices\":[{\"message\":{\"content\":\""+content+"\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":"+input+",\"completion_tokens\":"+output+",\"total_tokens\":"+(input+output)+"}}";
    }
    @Test void reconcilesAcceptedCallsAfterCrash() {
        var profile=profile(); var request=request(profile); quota.grant(admin,user.workspaceId(),10000,"Budget",UUID.randomUUID());
        disconnect=true; var result=gateway.judge(request);
        assertThat(result.status()).isEqualTo("PENDING_RECONCILIATION"); assertThat(requests.get()).isEqualTo(1);
        assertThat(quota.wallet(user).reserved()).isEqualTo(1020);
        var restarted=new ModelGateway(new JdbcModelCallRepository(jdbc,tx,profiles,quota),client,Clock.fixed(now.plusSeconds(120),ZoneOffset.UTC));
        restarted.recover(); assertThat(restarted.judge(request).status()).isEqualTo("PENDING_RECONCILIATION");
        assertThat(requests.get()).isEqualTo(1); assertThat(quota.wallet(user).reserved()).isEqualTo(1020);
        UUID key=UUID.randomUUID(); restarted.reconcile(admin,request.callId(),10,5,"Provider usage verified",key);
        restarted.reconcile(admin,request.callId(),10,5,"Provider usage verified",key);
        assertThat(quota.wallet(user).consumed()).isEqualTo(15); assertThat(quota.wallet(user).reserved()).isZero();
        assertThatThrownBy(() -> restarted.reconcile(admin,request.callId(),10,6,"Provider usage verified",key)).isInstanceOf(ModelCallConflictException.class);
        assertThatThrownBy(() -> restarted.reconcile(user,request.callId(),10,5,"Unapproved",UUID.randomUUID())).isInstanceOf(ScopeViolationException.class);
    }
    @Test void quotaPrecedesSendAndInvalidJudgeJsonStillConsumesUsage() {
        var profile=profile(); var request=request(profile);
        assertThat(gateway.judge(request).status()).isEqualTo("WAITING_QUOTA"); assertThat(requests.get()).isZero();
        quota.grant(admin,user.workspaceId(),2000,"Budget",UUID.randomUUID());
        var result=gateway.judge(request); assertThat(result.status()).isEqualTo("SUCCEEDED"); assertThat(result.content()).isEqualTo("not JSON");
        assertThat(result.credits()).isEqualTo(15); assertThat(gateway.judge(request)).isEqualTo(result); assertThat(requests.get()).isEqualTo(1);
        assertThat(capturedAuthorization).isEqualTo("Bearer "+providerKey); assertThat(capturedBody).doesNotContain(providerKey,"base_url","tools");
        assertThat(jdbc.sql("select encrypted_api_key from model_profiles where id=?").param(profile.id()).query(String.class).single()).doesNotContain(providerKey);
        assertThat(quota.wallet(user).consumed()).isEqualTo(15);
    }
    @Test void boundViolationDisablesProfileAndRetainsAccountableUsage() {
        var profile=profile(); quota.grant(admin,user.workspaceId(),1200,"Budget",UUID.randomUUID());
        reply=response("bad",2000,5); var request=request(profile);
        assertThat(gateway.judge(request).status()).isEqualTo("PENDING_RECONCILIATION");
        assertThat(profiles.require(profile.id()).enabled()).isFalse(); assertThat(quota.wallet(user).reserved()).isEqualTo(1020);
        assertThat(gateway.pending(admin,100).stream().filter(item -> item.callId().equals(request.callId())).findFirst().orElseThrow().reportedCredits()).isEqualTo(2005);
        assertThat(gateway.judge(request(profile)).status()).isEqualTo("MODEL_DISABLED"); assertThat(requests.get()).isEqualTo(1);
        var key=UUID.randomUUID();
        assertThatThrownBy(() -> gateway.reconcile(admin,request.callId(),2000,5,"Confirmed overage",key)).isInstanceOf(InsufficientQuotaException.class);
        assertThat(quota.wallet(user).reserved()).isEqualTo(1020); assertThat(quota.wallet(user).consumed()).isZero();
        quota.grant(admin,user.workspaceId(),1000,"Cover verified overage",UUID.randomUUID());
        gateway.reconcile(admin,request.callId(),2000,5,"Confirmed overage",key);
        assertThat(quota.wallet(user).consumed()).isEqualTo(2005); assertThat(quota.wallet(user).reserved()).isZero();
    }
    @Test void recoversSendIntentWithoutResendingAndRejectsUnboundedProfiles() {
        var profile=profile(); quota.grant(admin,user.workspaceId(),10000,"Budget",UUID.randomUUID()); var request=request(profile);
        var prepared=calls.prepare(request,now); calls.markSendIntent(prepared.dispatch(),now);
        var restarted=new ModelGateway(calls,client,Clock.fixed(now.plusSeconds(120),ZoneOffset.UTC)); restarted.recover();
        assertThat(restarted.judge(request).status()).isEqualTo("PENDING_RECONCILIATION"); assertThat(requests.get()).isZero();
        assertThatThrownBy(() -> new ModelProfileService(profiles).create(admin,new ModelProfileCommand("bad","http://unapproved.local/v1/chat/completions",providerKey,"test",0,100,1000,2,"",1,1,1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PriceVersion(UUID.randomUUID(),Long.MAX_VALUE,Long.MAX_VALUE,1).credits(2,2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PriceVersion(UUID.randomUUID(),1,1,3).credits(1,1)).isEqualTo(1);
    }
    @Test void uncertainCallsHoldSlotsAndMissingUsageDoesNotBecomeFree() {
        var profile=profile(); quota.grant(admin,user.workspaceId(),10000,"Budget",UUID.randomUUID());
        reply="{\"id\":\"accepted-without-usage\",\"choices\":[{\"message\":{\"content\":\"unknown\"}}]}";
        var first=request(profile); var second=request(profile); var third=request(profile);
        assertThat(gateway.judge(first).status()).isEqualTo("PENDING_RECONCILIATION");
        assertThat(gateway.judge(second).status()).isEqualTo("PENDING_RECONCILIATION");
        assertThat(gateway.judge(third).status()).isEqualTo("WAITING_CAPACITY");
        assertThat(quota.wallet(user).reserved()).isEqualTo(2040); assertThat(quota.wallet(user).consumed()).isZero(); assertThat(requests.get()).isEqualTo(2);
        gateway.reconcile(admin,first.callId(),0,0,"Provider confirms zero billed usage",UUID.randomUUID());
        reply=response("not JSON",10,5);
        assertThat(gateway.judge(third).status()).isEqualTo("SUCCEEDED"); assertThat(requests.get()).isEqualTo(3);
    }
    @Test void secretEchoInDecodedResponseAndRawJsonIsRedacted() {
        var profile=profile(); quota.grant(admin,user.workspaceId(),2000,"Budget",UUID.randomUUID());
        reply=response(providerKey,10,5).replace("\"id\":\"provider-call\"","\""+providerKey+"\":\"echoed field name\",\"id\":\"provider-call\"");
        var result=gateway.judge(request(profile));
        assertThat(result.content()).doesNotContain(providerKey);
        assertThat(result.rawResponse()).doesNotContain(providerKey);
    }
    @Test void reconciliationKeyCannotBeReusedAcrossCalls() {
        var profile=profile(); quota.grant(admin,user.workspaceId(),10000,"Budget",UUID.randomUUID()); disconnect=true;
        var first=request(profile); var second=request(profile);
        gateway.judge(first); gateway.judge(second); var key=UUID.randomUUID();
        gateway.reconcile(admin,first.callId(),10,5,"First confirmation",key);
        gateway.reconcile(admin,second.callId(),10,5,"Second confirmation",UUID.randomUUID());
        assertThatThrownBy(() -> gateway.reconcile(admin,second.callId(),10,5,"Second confirmation",key)).isInstanceOf(ModelCallConflictException.class);
    }
    @Test void concurrentDeliveryOfSameCallSendsOnlyOnce() throws Exception {
        var profile=profile(); quota.grant(admin,user.workspaceId(),2000,"Budget",UUID.randomUUID()); var request=request(profile);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<java.util.concurrent.Future<JudgeResponse>>();
            for(int index=0;index<20;index++) futures.add(pool.submit(() -> gateway.judge(request)));
            for(var future:futures) assertThat(future.get(20,java.util.concurrent.TimeUnit.SECONDS).status()).isIn("RESERVED","SEND_INTENT","SUCCEEDED");
        }
        assertThat(gateway.judge(request).status()).isEqualTo("SUCCEEDED"); assertThat(requests.get()).isEqualTo(1);
        assertThat(quota.wallet(user).consumed()).isEqualTo(15); assertThat(quota.wallet(user).reserved()).isZero();
    }
    @Test void settlementFailureAfterProviderResponseRetainsHoldWithoutReplay() {
        var profile=profile(); quota.grant(admin,user.workspaceId(),2000,"Budget",UUID.randomUUID()); var request=request(profile);
        jdbc.sql("create function model_test_failure() returns trigger language plpgsql as $$ begin if NEW.entry_type='SETTLE' then raise exception 'injected failure'; end if; return NEW; end $$").update();
        jdbc.sql("create trigger model_test_failure before insert on quota_ledger for each row execute function model_test_failure()").update();
        try {
            assertThatThrownBy(() -> gateway.judge(request)).isInstanceOf(RuntimeException.class);
            assertThat(requests.get()).isEqualTo(1); assertThat(quota.wallet(user).reserved()).isEqualTo(1020); assertThat(quota.wallet(user).consumed()).isZero();
        } finally { jdbc.sql("drop trigger model_test_failure on quota_ledger").update(); jdbc.sql("drop function model_test_failure()").update(); }
        var restarted=new ModelGateway(calls,client,Clock.fixed(now.plusSeconds(120),ZoneOffset.UTC)); restarted.recover();
        assertThat(restarted.judge(request).status()).isEqualTo("PENDING_RECONCILIATION"); assertThat(requests.get()).isEqualTo(1);
        restarted.reconcile(admin,request.callId(),10,5,"Provider confirms accepted usage",UUID.randomUUID());
        assertThat(quota.wallet(user).consumed()).isEqualTo(15);
    }
}
