package io.agenteval.quota;

import io.agenteval.api.PlatformApiApplication;
import io.agenteval.identity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=PlatformApiApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.servlet.session.cookie.secure=false","app.secure-cookies=false"})
class QuotaApiIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) { p.add("spring.datasource.url",postgres::getJdbcUrl); p.add("spring.datasource.username",postgres::getUsername); p.add("spring.datasource.password",postgres::getPassword); }
    @Autowired IdentityService identities;
    @Autowired ObjectMapper json;
    @Test void administratorCanSeeEachWalletWithoutExposingOtherUsersToOrdinaryAccounts() throws Exception {
        var owner=identities.createUser(UUID.randomUUID()+"@wallet-overview.local","Temporary-Password-1",false);
        var second=identities.createUser(UUID.randomUUID()+"@wallet-overview.local","Temporary-Password-1",false);
        var admin=identities.createUser(UUID.randomUUID()+"@wallet-overview.local","Temporary-Password-1",true);
        try(var user=login(owner.email());var management=login(admin.email())) {
            String moneyPath="/api/v1/admin/workspaces/"+owner.workspaceId()+"/quota/adjustments";
            assertThat(post(management,moneyPath,json.writeValueAsString(Map.of("amount","5","reason","Test monetary budget","idempotency_key",UUID.randomUUID())),true).statusCode()).isEqualTo(200);
            String secondPath="/api/v1/admin/workspaces/"+second.workspaceId()+"/quota/adjustments";
            assertThat(post(management,secondPath,json.writeValueAsString(Map.of("amount","0.000077","reason","Test monetary budget","idempotency_key",UUID.randomUUID())),true).statusCode()).isEqualTo(200);
            assertThat(get(user,"/api/v1/admin/users").statusCode()).isEqualTo(403);
            var response=get(management,"/api/v1/admin/users?limit=100");
            assertThat(response.statusCode()).isEqualTo(200);
            var rows=json.readTree(response.body());
            var wallets=new HashMap<String,com.fasterxml.jackson.databind.JsonNode>();
            rows.forEach(row->wallets.put(row.path("workspace_id").asText(),row.path("quota")));
            assertThat(wallets.get(owner.workspaceId().toString()).path("available").asText()).isEqualTo("5000000");
            assertThat(wallets.get(owner.workspaceId().toString()).has("unit")).isFalse();
            assertThat(wallets.get(second.workspaceId().toString()).path("available").asText()).isEqualTo("77");
            assertThat(wallets.get(second.workspaceId().toString()).has("unit")).isFalse();
            assertThat(response.body()).doesNotContain("password_hash","encrypted_api_key","Temporary-Password-1");
        }
    }
    @LocalServerPort int port;
    @Test void quotaAdjustmentRequiresAdminCsrfAndStableIdempotencyKey() throws Exception {
        var owner=identities.createUser(UUID.randomUUID()+"@quota.local","Temporary-Password-1",false);
        var admin=identities.createUser(UUID.randomUUID()+"@quota.local","Temporary-Password-1",true);
        try(var user=login(owner.email()); var management=login(admin.email()); var anonymous=HttpClient.newHttpClient()) {
            assertThat(get(anonymous,"/api/v1/quota").statusCode()).isEqualTo(401);
            assertThat(json.readTree(get(user,"/api/v1/quota").body()).path("available").asLong()).isZero();
            String path="/api/v1/admin/workspaces/"+owner.workspaceId()+"/quota/adjustments";
            String body=json.writeValueAsString(Map.of("amount","0.0001","reason","Initial cumulative budget","idempotency_key",UUID.randomUUID()));
            assertThat(post(user,path,body,true).statusCode()).isEqualTo(403);
            assertThat(post(management,path,body,false).statusCode()).isEqualTo(403);
            assertThat(post(management,path,body,true).statusCode()).isEqualTo(200);
            assertThat(post(management,path,body,true).statusCode()).isEqualTo(200);
            var changed=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(body); changed.put("amount","0.000101");
            assertThat(post(management,path,json.writeValueAsString(changed),true).statusCode()).isEqualTo(409);
            assertThat(json.readTree(get(user,"/api/v1/quota").body()).path("available").asLong()).isEqualTo(100);
            assertThat(json.readTree(get(management,"/api/v1/quota").body()).path("available").asLong()).isZero();
            assertThat(json.readTree(get(user,"/api/v1/quota/ledger").body())).hasSize(1);
            assertThat(json.readTree(get(management,"/api/v1/quota/ledger").body())).hasSize(0);
            var precise=json.writeValueAsString(Map.of("amount","9007199254.740993","reason","Exact integer budget","idempotency_key",UUID.randomUUID()));
            assertThat(post(management,path,precise,true).statusCode()).isEqualTo(200);
            var wallet=json.readTree(get(user,"/api/v1/quota").body());
            assertThat(wallet.path("available").isTextual()).isTrue(); assertThat(wallet.path("available").asText()).isEqualTo("9007199254741093");
            var ledgerPage=json.readTree(get(user,"/api/v1/quota/ledger/page?limit=1").body());
            assertThat(ledgerPage.path("items")).hasSize(1); assertThat(ledgerPage.path("items").get(0).path("granted_delta").isTextual()).isTrue();
            assertThat(ledgerPage.path("next_cursor").isTextual()).isTrue();
            assertThat(get(management,"/api/v1/quota/ledger/page?limit=1&cursor="+ledgerPage.path("next_cursor").asText()).statusCode()).isEqualTo(400);
        }
    }
    URI uri(String path) { return URI.create("http://localhost:"+port+path); }
    HttpResponse<String> get(HttpClient client,String path) throws Exception { return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    HttpResponse<String> post(HttpClient client,String path,String body,boolean csrf) throws Exception {
        var request=HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json");
        if(csrf) { var token=json.readTree(get(client,"/api/v1/auth/csrf").body()); request.header(token.path("header_name").asText(),token.path("token").asText()); }
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpClient login(String email) throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var csrf=json.readTree(get(client,"/api/v1/auth/csrf").body());
        var request=HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Content-Type","application/x-www-form-urlencoded").header(csrf.path("header_name").asText(),csrf.path("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(email,StandardCharsets.UTF_8)+"&password=Temporary-Password-1")).build();
        assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(post(client,"/api/v1/auth/password","{\"current_password\":\"Temporary-Password-1\",\"new_password\":\"Updated-Password-22\"}",true).statusCode()).isEqualTo(204);
        return client;
    }
}
