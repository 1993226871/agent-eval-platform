package io.agenteval.model;
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
@SpringBootTest(classes=PlatformApiApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.servlet.session.cookie.secure=false","app.secure-cookies=false","app.models.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","app.models.endpoint-origins=https://judge.example.test"})
class ModelAdminApiIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) { p.add("spring.datasource.url",postgres::getJdbcUrl); p.add("spring.datasource.username",postgres::getUsername); p.add("spring.datasource.password",postgres::getPassword); }
    @Autowired IdentityService identities;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @Test void onlyAdministratorCreatesModelAndCredentialsNeverReturn() throws Exception {
        var owner=identities.createUser(UUID.randomUUID()+"@model.local","Temporary-Password-1",false);
        var admin=identities.createUser(UUID.randomUUID()+"@model.local","Temporary-Password-1",true);
        String key="test-model-key-private-123456789";
        try(var user=login(owner.email());var management=login(admin.email())) {
            String account=json.writeValueAsString(Map.of("email",UUID.randomUUID()+"@created.local","temporary_password","Temporary-Password-999"));
            assertThat(post(user,"/api/v1/admin/users",account).statusCode()).isEqualTo(403);
            var provisioned=post(management,"/api/v1/admin/users",account); assertThat(provisioned.statusCode()).isEqualTo(201);
            assertThat(provisioned.body()).doesNotContain("Temporary-Password-999","password_hash");
            assertThat(json.readTree(provisioned.body()).path("must_change_password").asBoolean()).isTrue();
            var accounts=get(management,"/api/v1/admin/users"); assertThat(accounts.statusCode()).isEqualTo(200);
            assertThat(accounts.body()).doesNotContain("password_hash","Temporary-Password-999");
            assertThat(post(management,"/api/v1/admin/users",account).statusCode()).isEqualTo(409);
            var agent=post(user,"/api/v1/agents","{\"name\":\"UI agent\",\"version\":\"v1\"}"); assertThat(agent.statusCode()).isEqualTo(201);
            String agentId=json.readTree(agent.body()).path("id").asText();
            var issued=post(user,"/api/v1/worker-credentials",json.writeValueAsString(Map.of("agent_ids",List.of(agentId)))); assertThat(issued.statusCode()).isEqualTo(201);
            String secret=json.readTree(issued.body()).path("secret").asText();
            var credentials=get(user,"/api/v1/worker-credentials"); assertThat(credentials.statusCode()).isEqualTo(200);
            assertThat(credentials.body()).doesNotContain(secret,"secret_hash","\"secret\""); assertThat(json.readTree(credentials.body())).hasSize(1);
            assertThat(json.readTree(get(management,"/api/v1/worker-credentials").body())).hasSize(0);
            String body=json.writeValueAsString(new ModelProfileCommand("Judge","https://judge.example.test/v1/chat/completions",key,"fixture-v1",1000,100,65536,2,"Fixture enforced input limit",1,1,100));
            assertThat(post(user,"/api/v1/admin/models",body).statusCode()).isEqualTo(403);
            var created=post(management,"/api/v1/admin/models",body);
            assertThat(created.statusCode()).isEqualTo(200);
            assertThat(created.body()).doesNotContain(key,"api_key","encrypted_api_key");
            assertThat(get(management,"/api/v1/admin/models").body()).doesNotContain(key,"api_key","encrypted_api_key");
            var choices=get(user,"/api/v1/models"); assertThat(choices.statusCode()).isEqualTo(200);
            assertThat(choices.body()).doesNotContain(key,"endpoint","encrypted_api_key");
            var id=json.readTree(created.body()).path("id").asText();
            var judgeTemplates=get(user,"/api/v1/templates/judges"); assertThat(judgeTemplates.statusCode()).isEqualTo(200);
            assertThat(json.readTree(judgeTemplates.body())).hasSize(3);
            String templateConfig=json.writeValueAsString(Map.of("model_profile_id",id,"price_version_id",json.readTree(created.body()).path("price").path("id").asText()));
            var copied=post(user,"/api/v1/templates/judges/content-coverage/copy",templateConfig);
            assertThat(copied.statusCode()).isEqualTo(200);
            var evaluator=json.readTree(copied.body()); assertThat(evaluator.path("content").path("config").path("prompt_version").asText()).isEqualTo("rubric-v1");
            assertThat(get(management,"/api/v1/catalog/"+evaluator.path("id").asText()).statusCode()).isEqualTo(404);
            var test=post(management,"/api/v1/admin/models/"+id+"/test",json.writeValueAsString(Map.of("call_id",UUID.randomUUID())));
            assertThat(test.statusCode()).isEqualTo(200); assertThat(json.readTree(test.body()).path("status").asText()).isEqualTo("WAITING_QUOTA");
            assertThat(get(management,"/api/v1/admin/model-calls").statusCode()).isEqualTo(200);
            assertThat(post(management,"/api/v1/admin/models/"+id+"/disable","{\"reason\":\"Maintenance\"}").statusCode()).isEqualTo(204);
            assertThat(json.readTree(get(user,"/api/v1/models").body())).hasSize(0);
            assertThat(post(user,"/api/v1/templates/judges/content-coverage/copy",templateConfig).statusCode()).isEqualTo(400);
            assertThat(post(management,"/api/v1/admin/models",body.replace("judge.example.test","forbidden.example.test")).statusCode()).isEqualTo(400);
            var exactPrice=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(body); exactPrice.put("input_rate","9007199254740993"); exactPrice.put("denominator","9223372036854775807");
            var precise=post(management,"/api/v1/admin/models",json.writeValueAsString(exactPrice)); assertThat(precise.statusCode()).isEqualTo(200);
            assertThat(json.readTree(precise.body()).path("price").path("input_rate").isTextual()).isTrue();
            assertThat(json.readTree(precise.body()).path("price").path("input_rate").asText()).isEqualTo("9007199254740993");
        }
    }
    URI uri(String path) { return URI.create("http://localhost:"+port+path); }
    HttpResponse<String> get(HttpClient client,String path) throws Exception { return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    HttpResponse<String> post(HttpClient client,String path,String body) throws Exception {
        var token=json.readTree(get(client,"/api/v1/auth/csrf").body());
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json").header(token.path("header_name").asText(),token.path("token").asText()).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpClient login(String email) throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var token=json.readTree(get(client,"/api/v1/auth/csrf").body());
        var request=HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Content-Type","application/x-www-form-urlencoded").header(token.path("header_name").asText(),token.path("token").asText()).POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(email,StandardCharsets.UTF_8)+"&password=Temporary-Password-1")).build();
        assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(post(client,"/api/v1/auth/password","{\"current_password\":\"Temporary-Password-1\",\"new_password\":\"Updated-Password-22\"}").statusCode()).isEqualTo(204);
        return client;
    }
}
