package io.agenteval.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenteval.api.PlatformApiApplication;
import io.agenteval.api.AdminBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes=PlatformApiApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"server.servlet.session.cookie.secure=false","app.secure-cookies=false"})
class PrivateWorkspaceIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11");
    @DynamicPropertySource static void database(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",postgres::getJdbcUrl);
        props.add("spring.datasource.username",postgres::getUsername);
        props.add("spring.datasource.password",postgres::getPassword);
    }
    @LocalServerPort int port;
    @Autowired IdentityService identities;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AdminBootstrap bootstrap;

    @Test void explicitAdminBootstrapDoesNotResetExistingCredentials() {
        String email="admin-"+UUID.randomUUID()+"@test.local";
        var account=bootstrap.initialize(email,"Temporary-Password-1");
        assertThat(account.admin()).isTrue();
        var replay=bootstrap.initialize(email,"Different-Password-22");
        assertThat(replay.userId()).isEqualTo(account.userId());
        assertThat(replay.passwordHash()).isEqualTo(account.passwordHash());
        var normal=identities.createUser("normal-"+UUID.randomUUID()+"@test.local","Temporary-Password-1",false);
        assertThatThrownBy(() -> bootstrap.initialize(normal.email(),"Temporary-Password-1")).isInstanceOf(IllegalStateException.class);
        assertThat(identities.requireAccount(normal.userId()).admin()).isFalse();
    }

    @Test void isolatesUsersAndRequiresPasswordChange() throws Exception {
        String suffix=UUID.randomUUID().toString();
        var a=identities.createUser("a-"+suffix+"@test.local","Temporary-Password-1",false);
        var b=identities.createUser("b-"+suffix+"@test.local","Temporary-Password-1",false);
        assertThat(a.workspaceId()).isNotEqualTo(b.workspaceId());
        var client=client();
        assertThat(login(client,a.email(),"Temporary-Password-1").statusCode()).isEqualTo(200);
        assertThat(get(client,"/api/v1/workspace").statusCode()).isEqualTo(403);
        assertThat(post(client,"/api/v1/auth/password","{\"current_password\":\"Temporary-Password-1\",\"new_password\":\"Updated-Password-22\"}",true).statusCode()).isEqualTo(204);
        var own=get(client,"/api/v1/workspace");
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(json.readTree(own.body()).get("id").asText()).isEqualTo(a.workspaceId().toString());
        assertThat(get(client,"/api/v1/workspaces/"+b.workspaceId()).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from workspaces where owner_user_id=?",Integer.class,a.userId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select password_hash from users where id=?",String.class,a.userId())).doesNotContain("Temporary-Password-1");
        assertThat(get(client,"/api/v1/auth/me").body()).doesNotContain("password_hash");
    }

    @Test void protectsMutationsAndLogsOutSession() throws Exception {
        var user=identities.createUser("csrf-"+UUID.randomUUID()+"@test.local","Temporary-Password-1",false);
        var client=client(); login(client,user.email(),"Temporary-Password-1");
        assertThat(post(client,"/api/v1/auth/password","{}",false).statusCode()).isEqualTo(403);
        assertThat(post(client,"/api/v1/auth/logout","{}",true).statusCode()).isEqualTo(204);
        assertThat(get(client,"/api/v1/auth/me").statusCode()).isEqualTo(401);
        assertThat(get(client(),"/api/v1/workspace").statusCode()).isEqualTo(401);
    }

    @Test void refusesWorkerBearerAsBrowserSessionAndHidesLoginFailures() throws Exception {
        var client=client();
        assertThat(login(client,"absent@test.local","bad-password").statusCode()).isEqualTo(401);
        assertThat(get(client,"/api/v1/workspace").statusCode()).isEqualTo(401);
        var request=HttpRequest.newBuilder(uri("/api/v1/workers/register")).header("Authorization","Bearer invalid")
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test void createsUserAndWorkspaceAtomicallyAndNormalizesEmail() {
        String email="Atomic-"+UUID.randomUUID()+"@test.local";
        var user=identities.createUser(email,"Temporary-Password-1",false);
        assertThat(user.email()).isEqualTo(email.toLowerCase(java.util.Locale.ROOT));
        assertThatThrownBy(() -> identities.createUser(email.toUpperCase(java.util.Locale.ROOT),"Temporary-Password-1",false)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select count(*) from workspaces w join users u on u.id=w.owner_user_id where u.email=?",Integer.class,user.email())).isEqualTo(1);
        assertThatThrownBy(() -> identities.createUser("bad", "short",false)).isInstanceOf(IllegalArgumentException.class);
    }

    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); }
    private URI uri(String path) { return URI.create("http://localhost:"+port+path); }
    private HttpResponse<String> get(HttpClient client,String path) throws Exception { return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    private HttpResponse<String> post(HttpClient client,String path,String body,boolean csrf) throws Exception {
        var builder=HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json");
        if(csrf) { JsonNode token=json.readTree(get(client,"/api/v1/auth/csrf").body()); builder.header(token.get("header_name").asText(),token.get("token").asText()); }
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> login(HttpClient client,String email,String password) throws Exception {
        var token=json.readTree(get(client,"/api/v1/auth/csrf").body());
        var request=HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Content-Type","application/x-www-form-urlencoded")
            .header(token.get("header_name").asText(),token.get("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(email,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8))).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
}
