package io.agenteval.security;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import io.agenteval.workers.WorkerCredentialService;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class WorkerSecurityConfig {
    @Bean @Order(1) SecurityFilterChain workerChain(HttpSecurity http,WorkerCredentialService credentials) throws Exception {
        return http.securityMatcher("/api/v1/workers/**","/api/v1/trials/**")
            .csrf(csrf -> csrf.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("WORKER"))
            .addFilterBefore(new WorkerAuthenticationFilter(credentials),UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req,res,error) -> ApiProblems.write(res,401,"INVALID_WORKER_CREDENTIAL")))
            .build();
    }
}
