package io.agenteval.evaluation;
import io.agenteval.agents.*;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import io.agenteval.experiments.*;
import io.agenteval.telemetry.*;
import com.fasterxml.jackson.databind.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class HumanReviewIT {
    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static DataSourceTransactionManager tx;
    static ObjectMapper json=new ObjectMapper();
    WorkspaceActor owner;
    HumanReviewService reviews;
    UUID resultId;
    @BeforeAll static void database() {
        postgres=new PostgreSQLContainer<>("postgres:17.11"); postgres.start();
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate(); jdbc=JdbcClient.create(ds); tx=new DataSourceTransactionManager(ds);
    }
    @AfterAll static void stop() { if(postgres!=null) postgres.stop(); }
    WorkspaceActor user(boolean admin) { return new IdentityService(new JdbcIdentityRepository(jdbc,tx),new BCryptPasswordEncoder(4)).createUser(UUID.randomUUID()+"@review.local","Temporary-Password-1",admin).actor(); }
    @BeforeEach void setup() throws Exception {
        owner=user(false); reviews=new HumanReviewService(new JdbcHumanReviewRepository(jdbc,tx),Clock.systemUTC());
        var catalog=new CatalogService(new JdbcCatalogRepository(jdbc,tx,json)); var agents=new AgentService(new JdbcAgentRepository(jdbc,tx));
        var experiments=new ExperimentService(new JdbcExperimentRepository(jdbc,tx,json),catalog,agents,Clock.systemUTC());
        var dataset=catalog.create(owner,ResourceKind.DATASET,"Orders",json.readTree("{\"cases\":[{\"instruction\":\"Check order amount\",\"input\":{},\"expected\":100}]}"));
        var metric=catalog.create(owner,ResourceKind.EVALUATOR,"Amount",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"amount\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{\"operator\":\"eq\",\"selector\":\"/output/amount\",\"expected\":100}}"));
        var suite=catalog.create(owner,ResourceKind.SUITE,"Checks",json.readTree("{\"entries\":[{\"evaluator_version\":\""+catalog.publish(owner,metric.id()).id()+"\",\"dimension\":\"outcome\",\"required_to_pass\":true,\"weight\":1}]}"));
        var agent=agents.create(owner,"orders","v1");
        UUID experiment=experiments.start(owner,new StartExperimentCommand(agent.id(),"v1",catalog.publish(owner,dataset.id()).id(),catalog.publish(owner,suite.id()).id(),1,Instant.now().plusSeconds(300),UUID.randomUUID().toString()));
        var trial=experiments.trials(owner,experiment).getFirst();
        jdbc.sql("update trials set status='SUCCEEDED',completed_at=?,completion=cast(? as jsonb) where id=?").params(java.sql.Timestamp.from(Instant.now().minusSeconds(31)),"{\"status\":\"SUCCEEDED\",\"output\":{\"amount\":100},\"final_sequence\":0,\"event_count\":0,\"dropped_count\":0,\"artifact_ids\":[]}",trial.id()).update();
        var evidence=new EvidenceService(new JdbcEvidenceRepository(jdbc,tx,json),Clock.systemUTC()); var snapshot=evidence.freeze(owner,trial.id(),false);
        assertThat(evidence.context(owner,snapshot.id()).taskInstruction()).isEqualTo("Check order amount");
        var evaluation=new EvaluationOrchestrator(new JdbcEvaluationRepository(jdbc,tx,json),evidence,new RuleEvaluator(),Clock.systemUTC()); evaluation.schedule(100); assertThat(evaluation.runNext()).isTrue();
        resultId=jdbc.sql("select job_id from evaluation_results where workspace_id=?").param(owner.workspaceId()).query(UUID.class).single();
    }
    @Test void appendOnlyReviewKeepsOriginalAutomaticResultAndProvenance() {
        String before=jdbc.sql("select payload::text from evaluation_results where job_id=?").param(resultId).query(String.class).single();
        var first=reviews.add(owner,resultId,new BigDecimal("0.25"),"Reference coverage was incomplete");
        var second=reviews.add(owner,resultId,new BigDecimal("0.5"),"Rechecked against the rubric");
        assertThat(first.resultId()).isEqualTo(resultId); assertThat(first.reviewerId()).isEqualTo(owner.userId()); assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(reviews.list(owner,resultId,100)).hasSize(2);
        assertThat(jdbc.sql("select payload::text from evaluation_results where job_id=?").param(resultId).query(String.class).single()).isEqualTo(before);
        assertThatThrownBy(() -> jdbc.sql("update human_reviews set reason='mutated' where id=?").param(first.id()).update()).isInstanceOf(RuntimeException.class);
    }
    @Test void neitherOtherUserNorAdministratorCanReviewPrivateResults() {
        var foreign=user(false); var admin=user(true);
        assertThatThrownBy(() -> reviews.add(foreign,resultId,BigDecimal.ZERO,"Foreign")).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(() -> reviews.list(admin,resultId,100)).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(() -> reviews.add(owner,resultId,new BigDecimal("1.1"),"Invalid")).isInstanceOf(IllegalArgumentException.class);
    }
}
