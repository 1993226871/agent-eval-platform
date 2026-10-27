package io.agenteval.model;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/models")
public class ModelController {
    private final ModelProfileService profiles;
    private final CurrentActor current;
    public ModelController(ModelProfileService profiles,CurrentActor current) { this.profiles=profiles; this.current=current; }
    public record Choice(UUID id,String name,String model,long inputTokenBound,int maxOutputTokens,ModelViews.Price price,String billingMode) {}
    @GetMapping public List<Choice> choices(Authentication auth) {
        return profiles.list(current.require(auth)).stream().filter(ModelProfile::enabled).map(profile -> new Choice(profile.id(),profile.name(),profile.model(),profile.inputTokenBound(),profile.maxOutputTokens(),ModelViews.Price.of(profile.price()),profile.billingMode())).toList();
    }
    @PostMapping("/connections") public Choice connect(Authentication auth,@RequestBody ModelConnection command) {
        var profile=profiles.connect(current.require(auth),command,false);
        return new Choice(profile.id(),profile.name(),profile.model(),profile.inputTokenBound(),profile.maxOutputTokens(),ModelViews.Price.of(profile.price()),profile.billingMode());
    }
    @PostMapping("/connections/{id}/disable") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void disable(Authentication auth,@PathVariable UUID id) { profiles.disableOwn(current.require(auth),id); }
}
