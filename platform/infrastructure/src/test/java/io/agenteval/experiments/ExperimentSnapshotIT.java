package io.agenteval.experiments;
import io.agenteval.agents.*;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ExperimentSnapshotIT {
    static PostgreSQLContainer<?> postgres;
    static JdbcTemplate sql;
    static JdbcClient jdbc;
    static DataSourceTransactionManager tx;
    static ObjectMapper json=new ObjectMapper();
    CatalogService catalog;
    AgentService agents;
    IdentityService identities;
    ExperimentService experiments;
    @BeforeAll static void database() {
        postgres=new PostgreSQLContainer<>("postgres:17.11"); postgres.start();
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate(); sql=new JdbcTemplate(ds); jdbc=JdbcClient.create(ds); tx=new DataSourceTransactionManager(ds);
    }
    @AfterAll static void stop() { if(postgres!=null) postgres.stop(); }
    @BeforeEach void services() {
        identities=new IdentityService(new JdbcIdentityRepository(jdbc,tx),new BCryptPasswordEncoder(4));
        catalog=new CatalogService(new JdbcCatalogRepository(jdbc,tx,json));
        agents=new AgentService(new JdbcAgentRepository(jdbc,tx));
        experiments=new ExperimentService(new JdbcExperimentRepository(jdbc,tx,json),catalog,agents,Clock.systemUTC());
    }
    @Test void lightweightListingsAreScopedAndNeverContainFrozenBodies() throws Exception {
        var actor=actor(); UUID id=experiments.start(actor,command(actor,"summary-key"));
        var listings=new io.agenteval.administration.JdbcWorkspaceListings(jdbc);
        assertThat(listings.experiments(actor,0,25)).hasSize(1);
        assertThat(listings.experiment(actor,id).id()).isEqualTo(id);
        assertThat(json.copy().findAndRegisterModules().valueToTree(listings.experiments(actor,0,25)).toString()).doesNotContain("snapshot","cases","instruction");
        assertThat(json.valueToTree(listings.catalog(actor,ResourceKind.DATASET,0,25)).toString()).doesNotContain("content","cases","instruction");
        assertThat(listings.experiments(actor(),0,25)).isEmpty();
        assertThatThrownBy(() -> listings.experiment(actor(),id)).isInstanceOf(ScopeViolationException.class);
        assertThatThrownBy(() -> listings.experiments(actor,-1,25)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyBodyListsRejectOversizedPagesBeforeReadingBodies() throws Exception {
        var actor=actor();
        var content=json.createObjectNode(); var task=content.putArray("cases").addObject(); task.put("instruction","Large case"); task.putObject("input").put("value","x".repeat(700_000));
        for(int i=0;i<13;i++) catalog.create(actor,ResourceKind.DATASET,"Dataset "+i,content);
        assertThatThrownBy(() -> catalog.list(actor,ResourceKind.DATASET,0,100)).isInstanceOf(io.agenteval.administration.ResourceReadLimitException.class);
        assertThat(catalog.list(actor,ResourceKind.DATASET,0,1)).hasSize(1);
    }
    @Test void rejectsMutationOfFrozenConfiguration() throws Exception {
        var actor=actor(); UUID id=experiments.start(actor,command(actor,"immutable-config"));
        assertThatThrownBy(() -> sql.update("update experiments set snapshot='{}'::jsonb where id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void freezesExactPlannedModelCreditsForAllCasesAndRepetitions() throws Exception {
        var owner=actor(); var base=command(owner,"estimate"); UUID model=UUID.randomUUID(),price=UUID.randomUUID();
        sql.update("insert into model_profiles(id,name,endpoint,encrypted_api_key,model,input_token_bound,max_output_tokens,max_request_bytes,max_concurrent,bound_reference,created_by) values(?,?,?,?,?,1,10,4096,1,'fixture',?)",model,"Judge","http://fixture/chat/completions","unused","judge",owner.userId());
        sql.update("insert into model_price_versions(id,profile_id,input_rate,output_rate,denominator) values(?,?,9007199254740993,0,1)",price,model);
        var evaluator=catalog.publish(owner,catalog.create(owner,ResourceKind.EVALUATOR,"Judge",json.readTree("{\"type\":\"LLM\",\"criterion_id\":\"judge\",\"threshold\":1,\"required_evidence\":[\"output\"],\"config\":{\"model_profile_id\":\""+model+"\",\"price_version_id\":\""+price+"\",\"max_input_bytes\":100,\"max_output_tokens\":1,\"prompt_version\":\"v1\",\"rubric\":{}}}")).id());
        var suite=catalog.publish(owner,catalog.create(owner,ResourceKind.SUITE,"Judge suite",json.readTree("{\"entries\":[{\"evaluator_version\":\""+evaluator.id()+"\",\"dimension\":\"outcome\",\"required_to_pass\":true,\"weight\":1}]}")).id());
        var command=new StartExperimentCommand(base.agentId(),base.agentVersion(),base.datasetVersionId(),suite.id(),3,base.deadline(),"estimated-start");
        UUID id=experiments.start(owner,command);
        var estimate=experiments.get(owner,id).snapshot().path("cost_estimate");
        assertThat(estimate.path("planned_credits").asText()).isEqualTo("54043195528445958");
        assertThat(estimate.path("planned_calls").asInt()).isEqualTo(6);
        assertThat(estimate.path("assumptions").get(0).path("price_version_id").asText()).isEqualTo(price.toString());
    }
    @Test void expandsCasesAtomicallyAndSurvivesCatalogEdits() throws Exception {
        var actor=actor(); var command=command(actor,"stable-key");
        UUID id=experiments.start(actor,command);
        assertThat(experiments.trials(actor,id)).hasSize(6);
        assertThat(experiments.start(actor,command)).isEqualTo(id);
        var dataset=catalog.version(actor,command.datasetVersionId());
        var draft=catalog.draft(actor,dataset.resourceId());
        catalog.update(actor,draft.id(),draft.revision(),"修改后",json.readTree("{\"cases\":[]}"));
        assertThat(experiments.get(actor,id).snapshot().at("/dataset/cases").size()).isEqualTo(2);
        var changed=new StartExperimentCommand(command.agentId(),"other-version",command.datasetVersionId(),command.suiteVersionId(),3,command.deadline(),"stable-key");
        assertThatThrownBy(() -> experiments.start(actor,changed)).isInstanceOf(CatalogConflictException.class);
        assertThatThrownBy(() -> experiments.get(actor(),id)).isInstanceOf(ScopeViolationException.class);
        services(); assertThat(experiments.trials(actor,id)).hasSize(6);
    }
    @Test void rollsBackSnapshotAndAllTrialsWhenEnqueueFails() throws Exception {
        var actor=actor(); var command=command(actor,"rollback-key");
        sql.execute("create or replace function fail_trial_insert() returns trigger language plpgsql as $$ begin raise exception 'injected queue failure'; end $$");
        sql.execute("create trigger reject_trial before insert on trials for each row execute function fail_trial_insert()");
        try {
            assertThatThrownBy(() -> experiments.start(actor,command)).isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForObject("select count(*) from experiments where workspace_id=?",Integer.class,actor.workspaceId())).isZero();
            assertThat(sql.queryForObject("select count(*) from trials where workspace_id=?",Integer.class,actor.workspaceId())).isZero();
        } finally { sql.execute("drop trigger reject_trial on trials"); }
    }
    @Test void rerunRetainsOwnedSourceAndRejectsForeignSource() throws Exception {
        var owner=actor(); var base=command(owner,"source"); UUID source=experiments.start(owner,base);
        var mapper=json.copy().findAndRegisterModules().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var body=mapper.valueToTree(base); ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("idempotencyKey","rerun").put("sourceExperimentId",source.toString());
        var rerun=mapper.treeToValue(body,StartExperimentCommand.class); UUID id=experiments.start(owner,rerun);
        assertThat(experiments.get(owner,id).snapshot().path("source_experiment_id").asText()).isEqualTo(source.toString());
        var other=actor(); UUID foreign=experiments.start(other,command(other,"unused"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("idempotencyKey","foreign-rerun").put("sourceExperimentId",foreign.toString());
        var invalid=mapper.treeToValue(body,StartExperimentCommand.class);
        assertThatThrownBy(() -> experiments.start(owner,invalid)).isInstanceOf(ScopeViolationException.class);
    }
    @Test void stateMachineRefusesTerminalResurrection() {
        var states=new TrialStateMachine();
        assertThat(states.canTransition(io.agenteval.protocol.TrialExecutionStatus.QUEUED,io.agenteval.protocol.TrialExecutionStatus.SUCCEEDED)).isFalse();
        assertThat(states.canTransition(io.agenteval.protocol.TrialExecutionStatus.RUNNING,io.agenteval.protocol.TrialExecutionStatus.SUCCEEDED)).isTrue();
        assertThat(states.canTransition(io.agenteval.protocol.TrialExecutionStatus.SUCCEEDED,io.agenteval.protocol.TrialExecutionStatus.RUNNING)).isFalse();
    }
    @Test void persistedQueueExpiresWithoutExecutingAgentCode() throws Exception {
        var actor=actor(); var id=experiments.start(actor,command(actor,"expired-queue"));
        var expiry=new QueuedTrialExpiry(jdbc,Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"),ZoneOffset.UTC));
        assertThat(expiry.expire(200)).isGreaterThanOrEqualTo(6);
        assertThat(experiments.trials(actor,id)).allMatch(trial -> trial.status()==io.agenteval.protocol.TrialExecutionStatus.TIMED_OUT);
        assertThat(expiry.expire(200)).isZero();
    }
    private WorkspaceActor actor() { return identities.createUser(UUID.randomUUID()+"@test.local","Temporary-Password-1",false).actor(); }
    private StartExperimentCommand command(WorkspaceActor actor,String key) throws Exception {
        var agent=agents.create(actor,"test-agent","v1");
        var ds=catalog.create(actor,ResourceKind.DATASET,"数据",json.readTree("{\"cases\":[{\"instruction\":\"a\",\"input\":{}},{\"instruction\":\"b\",\"input\":{}}]}"));
        var dv=catalog.publish(actor,ds.id());
        var ev=catalog.create(actor,ResourceKind.EVALUATOR,"规则",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"ok\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{}}"));
        var evv=catalog.publish(actor,ev.id());
        var suite=catalog.create(actor,ResourceKind.SUITE,"套件",json.readTree("{\"entries\":[{\"evaluator_version\":\""+evv.id()+"\",\"dimension\":\"outcome\",\"required_to_pass\":true,\"weight\":1}]}"));
        return new StartExperimentCommand(agent.id(),"v1",dv.id(),catalog.publish(actor,suite.id()).id(),3,Instant.parse("2099-10-25T23:59:00Z"),key);
    }
}
