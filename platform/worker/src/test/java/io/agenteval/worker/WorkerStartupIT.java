package io.agenteval.worker;
import io.agenteval.identity.*;
import io.agenteval.agents.*;
import io.agenteval.catalog.*;
import io.agenteval.experiments.*;
import io.agenteval.protocol.TrialExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=PlatformWorkerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="spring.flyway.enabled=true")
class WorkerStartupIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) { p.add("spring.datasource.url",postgres::getJdbcUrl); p.add("spring.datasource.username",postgres::getUsername); p.add("spring.datasource.password",postgres::getPassword); }
    @Autowired IdentityRepository users;
    @Autowired CatalogRepository resources;
    @Autowired AgentRepository agentRepository;
    @Autowired ExperimentRepository experimentRepository;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired MaintenanceJobs maintenance;
    @Test void nonWebWorkerExpiresDurableTasksUsingSharedRepositories() throws Exception {
        var identities=new IdentityService(users,new BCryptPasswordEncoder(4));
        var actor=identities.createUser("worker-"+UUID.randomUUID()+"@test.local","Temporary-Password-1",false).actor();
        var catalog=new CatalogService(resources); var agents=new AgentService(agentRepository);
        var agent=agents.create(actor,"local","v1");
        var ds=catalog.create(actor,ResourceKind.DATASET,"任务",json.readTree("{\"cases\":[{\"instruction\":\"query\",\"input\":{}}]}"));
        var ev=catalog.create(actor,ResourceKind.EVALUATOR,"规则",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"ok\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{}}"));
        var evv=catalog.publish(actor,ev.id());
        var suite=catalog.create(actor,ResourceKind.SUITE,"体系",json.readTree("{\"entries\":[{\"evaluator_version\":\""+evv.id()+"\",\"dimension\":\"outcome\",\"required_to_pass\":true,\"weight\":1}]}"));
        var service=new ExperimentService(experimentRepository,catalog,agents,Clock.offset(clock,Duration.ofMinutes(-2)));
        var id=service.start(actor,new StartExperimentCommand(agent.id(),"v1",catalog.publish(actor,ds.id()).id(),catalog.publish(actor,suite.id()).id(),1,clock.instant().minusSeconds(60),"expired-worker-case"));
        maintenance.expireQueuedTrials();
        assertThat(service.trials(actor,id).getFirst().status()).isEqualTo(TrialExecutionStatus.TIMED_OUT);
    }
}
