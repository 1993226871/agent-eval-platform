package io.agenteval.workers;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/worker-credentials")
public class CredentialController {
    private final WorkerCredentialService credentials;
    private final CurrentActor current;
    public CredentialController(WorkerCredentialService credentials,CurrentActor current) { this.credentials=credentials; this.current=current; }
    public record IssueRequest(Set<UUID> agentIds) {}
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public IssuedCredential issue(Authentication auth,@RequestBody IssueRequest request) { return credentials.issue(current.require(auth),request.agentIds()); }
    @DeleteMapping("/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void revoke(Authentication auth,@PathVariable UUID id) { credentials.revoke(current.require(auth),id); }
}
