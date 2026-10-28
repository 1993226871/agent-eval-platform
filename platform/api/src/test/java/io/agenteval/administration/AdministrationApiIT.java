package io.agenteval.administration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenteval.api.PlatformApiApplication;
import io.agenteval.identity.*;
import io.agenteval.agents.AgentService;
import io.agenteval.catalog.TemplateService;
import io.agenteval.experiments.*;
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
class AdministrationApiIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) { properties.add("spring.datasource.url",postgres::getJdbcUrl); properties.add("spring.datasource.username",postgres::getUsername); properties.add("spring.datasource.password",postgres::getPassword); }
    @Autowired IdentityService identity;
    @Autowired AgentService agents;
    @Autowired TemplateService templates;
    @Autowired io.agenteval.catalog.CatalogService catalog;
    @Autowired ExperimentService experiments;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @LocalServerPort int port;
    @Test void enforcesHttpCapacityAndPrivateDeletionWithoutAdminBypass() throws Exception {
        var account=identity.createUser(UUID.randomUUID()+"@lifecycle.local","Temporary-Password-1",false); var actor=account.actor();
        var administrator=identity.createUser(UUID.randomUUID()+"@admin.local","Temporary-Password-1",true);
        var agent=agents.create(actor,"Orders","v1"); var bundle=templates.copyStarter(actor);
        UUID experiment=experiments.start(actor,new StartExperimentCommand(agent.id(),"v1",bundle.datasetVersion().id(),bundle.suiteVersion().id(),2,Instant.now().plusSeconds(3600),UUID.randomUUID().toString()));
        try(var owner=login(account.email()); var admin=login(administrator.email())) {
            var summary=get(owner,"/api/v1/experiment-summaries/"+experiment);
            assertThat(summary.statusCode()).isEqualTo(200);
            var details=json.readTree(summary.body());
            assertThat(details.path("agent_name").asText()).isEqualTo("Orders");
            assertThat(details.path("dataset_name").asText()).isEqualTo(bundle.datasetVersion().name());
            assertThat(details.path("dataset_version_number").asInt()).isEqualTo(bundle.datasetVersion().number());
            assertThat(details.path("suite_name").asText()).isEqualTo(bundle.suiteVersion().name());
            assertThat(details.path("case_count").asInt()).isEqualTo(1);
            assertThat(details.path("task_count").asInt()).isEqualTo(2);
            var draft=catalog.draft(actor,bundle.datasetVersion().resourceId());
            catalog.update(actor,draft.id(),draft.revision(),"Renamed draft",draft.content());
            assertThat(json.readTree(get(owner,"/api/v1/experiment-summaries/"+experiment).body()).path("dataset_name").asText()).isEqualTo(bundle.datasetVersion().name());
            var listed=json.readTree(get(owner,"/api/v1/experiment-summaries").body());
            assertThat(listed.get(0).path("agent_name").asText()).isEqualTo("Orders");
            assertThat(details.path("created_at").asText()).isNotBlank();
            assertThat(summary.body()).doesNotContain("instruction","expected","order_id");
            assertThat(get(admin,"/api/v1/experiment-summaries/"+experiment).statusCode()).isEqualTo(404);
            assertThat(get(owner,"/api/v1/resource-limits").statusCode()).isEqualTo(200);
            String configuration="/api/v1/admin/workspaces/"+actor.workspaceId()+"/resource-limits";
            String body=json.writeValueAsString(Map.of("limits",new ResourceLimits(5,2,1073741824L,1,300,1000,10000,50000,3600),"reason","One active experiment"));
            assertThat(mutate(owner,"POST",configuration,body).statusCode()).isEqualTo(403);
            assertThat(mutate(admin,"POST",configuration,body).statusCode()).isEqualTo(200);
            String start=json.writeValueAsString(new StartExperimentCommand(agent.id(),"v1",bundle.datasetVersion().id(),bundle.suiteVersion().id(),1,Instant.now().plusSeconds(3600),UUID.randomUUID().toString()));
            assertThat(mutate(owner,"POST","/api/v1/experiments",start).statusCode()).isEqualTo(429);
            assertThat(jdbc.sql("select count(*) from experiments where workspace_id=?").param(actor.workspaceId()).query(Long.class).single()).isEqualTo(1);
            var audit=get(owner,"/api/v1/audit-events?limit=100"); assertThat(audit.statusCode()).isEqualTo(200);
            assertThat(audit.body()).contains("RESOURCE_LIMITS_CONFIGURED").doesNotContain("Temporary-Password-1","password_hash","snapshot");
            String deletion=json.writeValueAsString(Map.of("idempotency_key",UUID.randomUUID()));
            assertThat(mutate(admin,"DELETE","/api/v1/experiments/"+experiment,deletion).statusCode()).isEqualTo(404);
            assertThat(mutate(owner,"DELETE","/api/v1/experiments/"+experiment,deletion).statusCode()).isEqualTo(202);
            assertThat(mutate(owner,"DELETE","/api/v1/experiments/"+experiment,deletion).statusCode()).isEqualTo(202);
            assertThat(get(owner,"/api/v1/experiments/"+experiment).statusCode()).isEqualTo(404);
            assertThat(get(owner,"/api/v1/reports/experiments/"+experiment).statusCode()).isEqualTo(404);
            assertThat(mutate(owner,"POST","/api/v1/experiments/"+experiment+"/cancel","{}").statusCode()).isEqualTo(404);
            var status=get(owner,"/api/v1/experiment-deletions/"+experiment); assertThat(status.statusCode()).isEqualTo(200);
            assertThat(json.readTree(status.body()).path("status").asText()).isEqualTo("DELETING");
            assertThat(get(admin,"/api/v1/experiment-deletions/"+experiment).statusCode()).isEqualTo(404);
        }
    }
    URI uri(String path) { return URI.create("http://localhost:"+port+path); }
    HttpResponse<String> get(HttpClient client,String path) throws Exception { return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    HttpResponse<String> mutate(HttpClient client,String method,String path,String body) throws Exception {
        var csrf=json.readTree(get(client,"/api/v1/auth/csrf").body());
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json").header(csrf.path("header_name").asText(),csrf.path("token").asText()).method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpClient login(String email) throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); var csrf=json.readTree(get(client,"/api/v1/auth/csrf").body());
        var response=client.send(HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Content-Type","application/x-www-form-urlencoded").header(csrf.path("header_name").asText(),csrf.path("token").asText()).POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(email,java.nio.charset.StandardCharsets.UTF_8)+"&password=Temporary-Password-1")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200); assertThat(mutate(client,"POST","/api/v1/auth/password","{\"current_password\":\"Temporary-Password-1\",\"new_password\":\"Updated-Password-22\"}").statusCode()).isEqualTo(204); return client;
    }
}
