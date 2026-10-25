package io.agenteval.api;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import io.agenteval.identity.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Clock;

@SpringBootApplication(scanBasePackages="io.agenteval")
public class PlatformApiApplication {
    public static void main(String[] args) { SpringApplication.run(PlatformApiApplication.class,args); }
    @Bean IdentityService identityService(IdentityRepository repository,PasswordEncoder encoder) { return new IdentityService(repository,encoder); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
