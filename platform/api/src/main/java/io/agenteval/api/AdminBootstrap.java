package io.agenteval.api;
import io.agenteval.identity.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Explicit local deployment bootstrap; there is no public registration endpoint. */
@Component
public final class AdminBootstrap implements ApplicationRunner {
    private final IdentityService identities;
    private final boolean enabled;
    private final String email,password;
    public AdminBootstrap(IdentityService identities,@Value("${app.bootstrap.enabled:false}") boolean enabled,
        @Value("${BOOTSTRAP_ADMIN_EMAIL:}") String email,@Value("${BOOTSTRAP_ADMIN_PASSWORD:}") String password) {
        this.identities=identities; this.enabled=enabled; this.email=email; this.password=password;
    }
    @Override public void run(ApplicationArguments arguments) { if(enabled) initialize(email,password); }
    public UserAccount initialize(String email,String temporaryPassword) {
        UserAccount existing;
        try { existing=identities.loadForLogin(email); }
        catch(ScopeViolationException missing) { return identities.createUser(email,temporaryPassword,true); }
        if(!existing.admin()) throw new IllegalStateException("Bootstrap cannot promote an existing account");
        return existing;
    }
}
