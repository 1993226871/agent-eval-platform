package io.agenteval.security;
import io.agenteval.identity.IdentityService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

final class PasswordChangeFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED=Set.of("/api/v1/auth/csrf","/api/v1/auth/me","/api/v1/auth/password","/api/v1/auth/logout");
    private final IdentityService identities;
    PasswordChangeFilter(IdentityService identities) { this.identities=identities; }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
            && !ALLOWED.contains(req.getServletPath()) && identities.requireAccount(UUID.fromString(auth.getName())).mustChangePassword()) {
            ApiProblems.write(res,403,"PASSWORD_CHANGE_REQUIRED"); return;
        }
        chain.doFilter(req,res);
    }
}
