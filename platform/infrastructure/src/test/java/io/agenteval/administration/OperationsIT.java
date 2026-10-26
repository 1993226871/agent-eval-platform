package io.agenteval.administration;

import io.agenteval.operations.DeploymentOperations;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class OperationsIT {
    @Test void migrationJobCompletesWithoutStartingApplicationAndIsRepeatable() throws Exception {
        try(var database=new PostgreSQLContainer<>("postgres:17.11")) {
            database.start();
            assertThat(DeploymentOperations.migrate(database.getJdbcUrl(),database.getUsername(),database.getPassword())).isGreaterThanOrEqualTo(19);
            assertThat(DeploymentOperations.migrate(database.getJdbcUrl(),database.getUsername(),database.getPassword())).isZero();
            try(var connection=DriverManager.getConnection(database.getJdbcUrl(),database.getUsername(),database.getPassword()); var query=connection.createStatement()) {
                assertThat(query.executeQuery("select count(*) from flyway_schema_history where success").next()).isTrue();
            }
            assertThat(DeploymentOperations.healthy(database.getJdbcUrl(),database.getUsername(),database.getPassword())).isTrue();
        }
    }
}
