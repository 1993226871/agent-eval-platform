package io.agenteval.security;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration
public class WorkerSecurityConfig {
    /** Closed until task-scoped Worker authentication is implemented in Task 5. */
    @Bean @Order(1) SecurityFilterChain workerChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/v1/workers/**","/api/v1/trials/**")
            .csrf(csrf -> csrf.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
            .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req,res,error) -> ApiProblems.write(res,401,"INVALID_WORKER_CREDENTIAL")))
            .build();
    }
}
