package io.agenteval.administration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AuditIT {
    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static DataSourceTransactionManager transactions;
    @BeforeAll static void database() {
        postgres=new PostgreSQLContainer<>("postgres:17.11"); postgres.start(); var source=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate(); jdbc=JdbcClient.create(source); transactions=new DataSourceTransactionManager(source);
    }
    @AfterAll static void stop() { if(postgres!=null) postgres.stop(); }
    @Test void keepsAppendOnlyScopedMetadataWithoutSecretPayloads() throws Exception {
        var identity=new IdentityService(new JdbcIdentityRepository(jdbc,transactions),new BCryptPasswordEncoder(4));
        var owner=identity.createUser(UUID.randomUUID()+"@audit.local","Temporary-Password-1",false).actor();
        var other=identity.createUser(UUID.randomUUID()+"@audit.local","Temporary-Password-1",false).actor();
        var json=new ObjectMapper().findAndRegisterModules(); var catalog=new CatalogService(new JdbcCatalogRepository(jdbc,transactions,json));
        var draft=catalog.create(owner,ResourceKind.EVALUATOR,"private name must not be logged",json.readTree("{\"type\":\"RULE\",\"criterion_id\":\"check\",\"threshold\":1,\"required_evidence\":[\"output\"],\"config\":{\"api_key\":\"never-log-this-secret\"}}"));
        var version=catalog.publish(owner,draft.id()); catalog.publish(owner,draft.id());
        var audits=new AuditService(new JdbcAuditRepository(jdbc));
        var page=audits.list(owner,null,100);
        assertThat(page.items()).anyMatch(event -> event.action().equals("CATALOG_CREATED") && event.entityId().equals(draft.id()));
        assertThat(page.items().stream().filter(event -> event.action().equals("VERSION_PUBLISHED") && event.entityId().equals(version.id()))).hasSize(1);
        assertThat(json.writeValueAsString(page)).doesNotContain("never-log-this-secret","private name must not be logged","Temporary-Password-1");
        assertThat(audits.list(other,null,100).items()).noneMatch(event -> event.entityId().equals(draft.id()) || event.entityId().equals(version.id()));
        var first=audits.list(owner,null,1); assertThat(first.nextCursor()).isNotNull();
        assertThatThrownBy(() -> audits.list(other,first.nextCursor(),1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.sql("update audit_events set action='MUTATED' where id=?").param(page.items().getFirst().id()).update()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from audit_events where workspace_id=?").param(owner.workspaceId()).update()).isInstanceOf(RuntimeException.class);
    }
}
