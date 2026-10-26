package io.agenteval.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenteval.administration.*;
import io.agenteval.agents.*;
import io.agenteval.catalog.*;
import io.agenteval.experiments.*;
import io.agenteval.identity.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class EvidenceFairnessIT {
    @Test void twoHundredBlockedOldTrialsCannotStarveAnotherWorkspaceAndBudgetIncreaseResumesThem() throws Exception {
        try(var database=new PostgreSQLContainer<>("postgres:17.11")) {
            database.start(); var source=new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());
            Flyway.configure().dataSource(source).load().migrate(); var jdbc=JdbcClient.create(source); var tx=new DataSourceTransactionManager(source);
            var json=new ObjectMapper(); var now=Instant.now();
            var identities=new IdentityService(new JdbcIdentityRepository(jdbc,tx),new BCryptPasswordEncoder(4));
            var admin=identities.createUser("admin@fairness.local","Temporary-Password-1",true).actor();
            var blocked=identities.createUser("blocked@fairness.local","Temporary-Password-1",false).actor();
            var healthy=identities.createUser("healthy@fairness.local","Temporary-Password-1",false).actor();
            var limits=new ResourceLimitService(new JdbcResourceLimitRepository(jdbc,tx));
            limits.configure(admin,blocked.workspaceId(),new ResourceLimits(5,2,1,200,300,1000,10000,50000,3600),"Fixture storage exhausted");
            var catalog=new CatalogService(new JdbcCatalogRepository(jdbc,tx,json)); var agents=new AgentService(new JdbcAgentRepository(jdbc,tx));
            var experiments=new ExperimentService(new JdbcExperimentRepository(jdbc,tx,json),catalog,agents,Clock.fixed(now,ZoneOffset.UTC));
            UUID healthyExperiment=null;
            for(var owner:List.of(blocked,healthy)) {
                var data=json.createObjectNode(); var cases=data.putArray("cases");
                for(int i=0;i<(owner==blocked?200:1);i++) cases.addObject().put("instruction","Check amount").putObject("input");
                var dataset=catalog.publish(owner,catalog.create(owner,ResourceKind.DATASET,"Cases",data).id()).id();
                var rule=catalog.publish(owner,catalog.create(owner,ResourceKind.EVALUATOR,"Amount",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"amount\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{\"operator\":\"exists\",\"selector\":\"/output\"}}")).id()).id();
                var suite=catalog.publish(owner,catalog.create(owner,ResourceKind.SUITE,"Suite",json.readTree("{\"entries\":[{\"evaluator_version\":\""+rule+"\",\"dimension\":\"outcome\",\"required_to_pass\":true,\"weight\":1}]}")).id()).id();
                UUID experiment=experiments.start(owner,new StartExperimentCommand(agents.create(owner,"Agent","v1").id(),"v1",dataset,suite,1,now.plusSeconds(3600),UUID.randomUUID().toString()));
                jdbc.sql("update trials set status='SUCCEEDED',completed_at=?,completion=cast(? as jsonb) where experiment_id=?")
                    .params(java.sql.Timestamp.from(now.minusSeconds(owner==blocked?32:31)),"{\"status\":\"SUCCEEDED\",\"output\":100,\"final_sequence\":0,\"event_count\":0,\"dropped_count\":0,\"artifact_ids\":[]}",experiment).update();
                if(owner==healthy) healthyExperiment=experiment;
            }
            var evidence=new JdbcEvidenceRepository(jdbc,tx,json);
            assertThat(evidence.freezeReady(200,now)).isZero();
            assertThat(evidence.freezeReady(200,now)).isEqualTo(1);
            assertThat(jdbc.sql("select count(*) from evidence_snapshots s join trials t on t.id=s.trial_id where t.experiment_id=?").param(healthyExperiment).query(Long.class).single()).isEqualTo(1);
            assertThat(evidence.freezeReady(200,now)).isZero();
            limits.configure(admin,blocked.workspaceId(),ResourceLimits.defaults(),"Resume waiting evidence");
            assertThat(evidence.freezeReady(200,now)).isEqualTo(200);
        }
    }
}
