package io.agenteval.admin;
import io.agenteval.model.*;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
public class ModelAdminController {
    private final ModelProfileService profiles;
    private final ModelGateway gateway;
    private final CurrentActor current;
    public ModelAdminController(ModelProfileService profiles,ModelGateway gateway,CurrentActor current) { this.profiles=profiles; this.gateway=gateway; this.current=current; }
    @PostMapping("/models") public ModelViews.Profile create(Authentication auth,@RequestBody ModelProfileCommand command) { return ModelViews.Profile.of(profiles.create(current.require(auth),command)); }
    @GetMapping("/models") public List<ModelViews.Profile> list(Authentication auth) { return profiles.list(current.require(auth)).stream().map(ModelViews.Profile::of).toList(); }
    @GetMapping("/model-calls") public List<ModelViews.Pending> pending(Authentication auth,@RequestParam(defaultValue="50") int limit) { return gateway.pending(current.require(auth),limit).stream().map(ModelViews.Pending::of).toList(); }
    public record Disable(String reason) {}
    @PostMapping("/models/{profileId}/disable") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void disable(Authentication auth,@PathVariable UUID profileId,@RequestBody Disable command) { profiles.disable(current.require(auth),profileId,command.reason()); }
    public record ConnectivityTest(UUID callId) {}
    @PostMapping("/models/{profileId}/test") public ReconciliationView test(Authentication auth,@PathVariable UUID profileId,@RequestBody ConnectivityTest command) {
        var actor=current.require(auth); if(!actor.admin()) throw new io.agenteval.identity.ScopeViolationException();
        var profile=profiles.list(actor).stream().filter(item -> item.id().equals(profileId)).findFirst().orElseThrow(io.agenteval.identity.ScopeViolationException::new);
        var result=gateway.judge(new JudgeRequest(actor.workspaceId(),command.callId(),profile.id(),profile.price().id(),"Return a JSON object with the key ok and value true.","Connectivity check",Math.min(16,profile.maxOutputTokens())));
        return new ReconciliationView(result.callId(),result.status(),ModelViews.exact(result.inputTokens()),ModelViews.exact(result.outputTokens()),ModelViews.exact(result.credits()));
    }
    public record Reconciliation(String inputTokens,String outputTokens,String reason,UUID idempotencyKey) {}
    @PostMapping("/model-calls/{callId}/reconciliation") public ReconciliationView reconcile(Authentication auth,@PathVariable UUID callId,@RequestBody Reconciliation command) {
        var result=gateway.reconcile(current.require(auth),callId,Long.parseLong(command.inputTokens()),Long.parseLong(command.outputTokens()),command.reason(),command.idempotencyKey());
        return new ReconciliationView(result.callId(),result.status(),ModelViews.exact(result.inputTokens()),ModelViews.exact(result.outputTokens()),ModelViews.exact(result.credits()));
    }
    public record ReconciliationView(UUID callId,String status,String inputTokens,String outputTokens,String credits) {}
}
