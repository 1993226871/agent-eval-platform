package io.agenteval.model;

import io.agenteval.identity.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class ModelKeyRotationIT {
    @Test void rotatesEveryCredentialAtomicallyAndPreservesProfileAndPriceIdentity() {
        try(var database=new PostgreSQLContainer<>("postgres:17.11")) {
            database.start(); var source=new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());
            Flyway.configure().dataSource(source).load().migrate(); var jdbc=JdbcClient.create(source); var tx=new DataSourceTransactionManager(source);
            var owner=new IdentityService(new JdbcIdentityRepository(jdbc,tx),new BCryptPasswordEncoder(4)).createUser(UUID.randomUUID()+"@rotation.local","Temporary-Password-1",true).actor();
            byte[] oldKey=new byte[32],newKey=new byte[32]; Arrays.fill(newKey,(byte)7);
            var oldBox=new ModelSecretBox(oldKey); var repository=new EncryptedModelProfileRepository(jdbc,tx,oldBox,Set.of("https://judge.example.test"));
            var models=new ModelProfileService(repository);
            var command=new ModelProfileCommand("Fixture","https://judge.example.test/v1/chat/completions","private-fixture-provider-key","fixture",1000,100,65536,1,"Fixture bound",1,1,1);
            var first=models.create(owner,command); var second=models.create(owner,command);
            var ids=List.of(first.id(),second.id()).stream().sorted().toList();
            String corrupt=new ModelSecretBox(newKey).encrypt(ids.getLast(),"private-fixture-provider-key");
            jdbc.sql("update model_profiles set encrypted_api_key=? where id=?").params(corrupt,ids.getLast()).update();
            var before=jdbc.sql("select encrypted_api_key from model_profiles order by id").query(String.class).list();
            assertThatThrownBy(() -> ModelKeyRotation.rotate(source,oldKey,newKey)).isInstanceOf(ModelUnavailableException.class);
            assertThat(jdbc.sql("select encrypted_api_key from model_profiles order by id").query(String.class).list()).isEqualTo(before);
            assertThat(jdbc.sql("select count(*) from audit_events where action='MODEL_MASTER_KEY_ROTATED'").query(Long.class).single()).isZero();
            jdbc.sql("update model_profiles set encrypted_api_key=? where id=?").params(oldBox.encrypt(ids.getLast(),"private-fixture-provider-key"),ids.getLast()).update();
            assertThat(ModelKeyRotation.rotate(source,oldKey,newKey)).isEqualTo(2);
            var rotated=new EncryptedModelProfileRepository(jdbc,tx,new ModelSecretBox(newKey),Set.of("https://judge.example.test"));
            assertThat(rotated.credential(first.id())).isEqualTo("private-fixture-provider-key");
            assertThat(rotated.credential(second.id())).isEqualTo("private-fixture-provider-key");
            assertThat(rotated.require(first.id()).price().id()).isEqualTo(first.price().id());
            assertThatThrownBy(() -> repository.credential(first.id())).isInstanceOf(ModelUnavailableException.class);
            assertThat(jdbc.sql("select count(*) from audit_events where action='MODEL_MASTER_KEY_ROTATED'").query(Long.class).single()).isEqualTo(2);
        }
    }
}
