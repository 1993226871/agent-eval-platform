package io.agenteval.security;
import io.agenteval.identity.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public final class CurrentActor {
    private final IdentityService identities;
    public CurrentActor(IdentityService identities) { this.identities=identities; }
    public WorkspaceActor require(Authentication authentication) {
        if(authentication==null || !authentication.isAuthenticated()) throw new ScopeViolationException();
        return identities.requireAccount(UUID.fromString(authentication.getName())).actor();
    }
}
