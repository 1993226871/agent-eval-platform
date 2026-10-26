package io.agenteval.model;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ModelSecretBoxTest {
    @Test void ciphertextIsRandomizedAndBoundToProfileAndDeploymentKey() {
        byte[] key=new byte[32]; new java.security.SecureRandom().nextBytes(key);
        var vault=new ModelSecretBox(key); var profile=UUID.randomUUID(); String secret="test-private-provider-key";
        String first=vault.encrypt(profile,secret),second=vault.encrypt(profile,secret);
        assertThat(first).isNotEqualTo(second).doesNotContain(secret);
        assertThat(vault.decrypt(profile,first)).isEqualTo(secret);
        assertThatThrownBy(() -> vault.decrypt(UUID.randomUUID(),first)).isInstanceOf(ModelUnavailableException.class);
        assertThatThrownBy(() -> new ModelSecretBox(new byte[32]).decrypt(profile,first)).isInstanceOf(ModelUnavailableException.class);
        assertThatThrownBy(() -> new ModelSecretBox(null).encrypt(profile,secret)).isInstanceOf(ModelUnavailableException.class);
    }
}
