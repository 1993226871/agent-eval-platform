package io.agenteval.security;
import io.agenteval.workers.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

final class WorkerAuthenticationFilter extends OncePerRequestFilter {
    private final WorkerCredentialService credentials;
    WorkerAuthenticationFilter(WorkerCredentialService credentials) { this.credentials=credentials; }
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String header=request.getHeader("Authorization");
        if(header==null || !header.startsWith("Bearer ")) { ApiProblems.write(response,401,"INVALID_WORKER_CREDENTIAL"); return; }
        WorkerPrincipal principal;
        try { principal=credentials.authenticate(header.substring(7)); }
        catch(InvalidWorkerCredentialException invalid) { ApiProblems.write(response,401,"INVALID_WORKER_CREDENTIAL"); return; }
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("ROLE_WORKER"))));
        SecurityContextHolder.setContext(context); chain.doFilter(request,response);
    }
}
