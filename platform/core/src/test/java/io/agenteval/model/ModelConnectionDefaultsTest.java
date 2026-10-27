package io.agenteval.model;

import io.agenteval.identity.ScopeViolationException;
import io.agenteval.identity.WorkspaceActor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelConnectionDefaultsTest {
    @Test void normalizesBaseUrlAndDerivesDeepSeekDefaultsWithoutBrowserPrices() {
        var command=new ModelConnection("deepseek-flash","https://api.deepseek.com","private-key-for-test-only");
        var resolved=ModelConnectionDefaults.resolve(command);
        assertThat(resolved.endpoint()).isEqualTo("https://api.deepseek.com/chat/completions");
        assertThat(resolved.name()).isEqualTo("deepseek-flash");
        assertThat(resolved.inputTokenBound()).isEqualTo(1_048_576);
        assertThat(resolved.maxOutputTokens()).isEqualTo(8192);
        assertThat(new PriceVersion(UUID.randomUUID(),resolved.inputRate(),resolved.outputRate(),resolved.denominator()).credits(1000,1000)).isEqualTo(10_000);
        assertThat(command.toString()).doesNotContain("private-key-for-test-only");
    }
    @Test void refusesUnknownModelsAndUnsafeEndpointShapesInsteadOfGuessingPrices() {
        assertThatThrownBy(()->ModelConnectionDefaults.resolve(new ModelConnection("unknown-model","https://api.deepseek.com","test-key"))).isInstanceOf(IllegalArgumentException.class);
        for(String endpoint:new String[]{"http://api.deepseek.com","https://user:secret@api.deepseek.com","https://api.deepseek.com?key=secret","https://api.deepseek.com/unrelated"})
            assertThatThrownBy(()->ModelConnectionDefaults.resolve(new ModelConnection("deepseek-flash",endpoint,"test-key"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(ModelConnectionDefaults.resolve(new ModelConnection("deepseek-flash","https://api.deepseek.com/v1/","test-key")).endpoint()).isEqualTo("https://api.deepseek.com/v1/chat/completions");
    }
    @Test void privateModelIsOnlyUsableByItsOwningWorkspaceEvenForAdministrators() {
        UUID owner=UUID.randomUUID();
        var model=new ModelProfile(UUID.randomUUID(),"private","https://api.deepseek.com/chat/completions","deepseek-flash",1048576,8192,65536,2,"verified",true,null,new PriceVersion(UUID.randomUUID(),2,8,1),owner,"BYOK");
        model.requireWorkspace(owner);
        assertThatThrownBy(()->model.requireWorkspace(UUID.randomUUID())).isInstanceOf(ScopeViolationException.class);
        assertThat(model.usesPlatformQuota()).isFalse();
        assertThat(model.visibleTo(new WorkspaceActor(UUID.randomUUID(),UUID.randomUUID(),true))).isFalse();
    }
}
