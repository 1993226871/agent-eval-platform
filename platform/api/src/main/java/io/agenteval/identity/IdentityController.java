package io.agenteval.identity;
import io.agenteval.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final IdentityService identities;
    private final CurrentActor current;
    public IdentityController(IdentityService identities,CurrentActor current) { this.identities=identities; this.current=current; }
    @GetMapping("/auth/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("header_name",token.getHeaderName(),"token",token.getToken()); }
    @GetMapping("/auth/me") public Map<String,Object> me(Authentication auth) {
        var account=identities.requireAccount(current.require(auth).userId());
        return Map.of("user_id",account.userId(),"workspace_id",account.workspaceId(),"email",account.email(),"admin",account.admin(),"must_change_password",account.mustChangePassword());
    }
    public record PasswordChange(String currentPassword,String newPassword) {}
    @PostMapping("/auth/password") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void password(Authentication auth,@RequestBody PasswordChange body,HttpServletRequest request,HttpServletResponse response) {
        identities.changePassword(current.require(auth),body.currentPassword(),body.newPassword());
        // Revoke old sessions, then explicitly persist a fresh session for this caller.
        var oldSession=request.getSession(false);
        if(oldSession!=null) oldSession.invalidate();
        var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        new org.springframework.security.web.context.HttpSessionSecurityContextRepository().saveContext(context,request,response);
    }
    @GetMapping("/workspace") public Map<String,UUID> workspace(Authentication auth) { return Map.of("id",current.require(auth).workspaceId()); }
    @GetMapping("/workspaces/{id}") public Map<String,UUID> workspaceById(Authentication auth,@PathVariable UUID id) {
        var actor=current.require(auth); WorkspaceScope.require(actor,id); return Map.of("id",id);
    }
}
