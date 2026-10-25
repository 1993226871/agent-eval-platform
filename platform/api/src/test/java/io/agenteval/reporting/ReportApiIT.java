package io.agenteval.reporting;
import io.agenteval.api.PlatformApiApplication;
import io.agenteval.identity.*;
import io.agenteval.agents.AgentService;
import io.agenteval.catalog.TemplateService;
import io.agenteval.experiments.*;
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
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=PlatformApiApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.servlet.session.cookie.secure=false","app.secure-cookies=false"})
class ReportApiIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) { p.add("spring.datasource.url",postgres::getJdbcUrl); p.add("spring.datasource.username",postgres::getUsername); p.add("spring.datasource.password",postgres::getPassword); }
    @Autowired IdentityService identities;
    @Autowired AgentService agents;
    @Autowired TemplateService templates;
    @Autowired ExperimentService experiments;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @Test void authenticatesReportsAndHidesOtherWorkspacesAndTaskReferences() throws Exception {
        var account=identities.createUser(UUID.randomUUID()+"@test.local","Temporary-Password-1",false); var actor=account.actor();
        var agent=agents.create(actor,"orders","v1"); var bundle=templates.copyStarter(actor);
        UUID experiment=experiments.start(actor,new StartExperimentCommand(agent.id(),"v1",bundle.datasetVersion().id(),bundle.suiteVersion().id(),1,Instant.now().plusSeconds(300),UUID.randomUUID().toString()));
        try(var anonymous=HttpClient.newHttpClient(); var owner=login(account.email()); var other=login(identities.createUser(UUID.randomUUID()+"@test.local","Temporary-Password-1",false).email())) {
            String path="/api/v1/reports/experiments/"+experiment;
            assertThat(get(anonymous,path).statusCode()).isEqualTo(401);
            var response=get(owner,path); assertThat(response.statusCode()).isEqualTo(200);
            var body=json.readTree(response.body()); assertThat(body.path("planned_attempts").asInt()).isEqualTo(1); assertThat(body.path("determinable_attempts").asInt()).isZero();
            assertThat(get(other,path).statusCode()).isEqualTo(404);
            var page=get(owner,path+"/trials?limit=1"); assertThat(page.statusCode()).isEqualTo(200); assertThat(json.readTree(page.body()).path("items")).hasSize(1);
            assertThat(page.body()).doesNotContain("instruction","expected","order_id");
        }
    }
    URI uri(String path) { return URI.create("http://localhost:"+port+path); }
    HttpResponse<String> get(HttpClient client,String path) throws Exception { return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    HttpClient login(String email) throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var csrf=json.readTree(get(client,"/api/v1/auth/csrf").body());
        var request=HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Content-Type","application/x-www-form-urlencoded").header(csrf.path("header_name").asText(),csrf.path("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(email,java.nio.charset.StandardCharsets.UTF_8)+"&password=Temporary-Password-1")).build();
        assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        csrf=json.readTree(get(client,"/api/v1/auth/csrf").body());
        request=HttpRequest.newBuilder(uri("/api/v1/auth/password")).header("Content-Type","application/json").header(csrf.path("header_name").asText(),csrf.path("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString("{\"current_password\":\"Temporary-Password-1\",\"new_password\":\"Updated-Password-22\"}")).build();
        assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204); return client;
    }
}
