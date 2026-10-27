package io.agenteval.model;
import io.agenteval.identity.*;
import java.util.List;
import java.util.UUID;
public final class ModelProfileService {
    private final ModelProfileRepository repository;
    public ModelProfileService(ModelProfileRepository repository) { this.repository=repository; }
    public ModelProfile create(WorkspaceActor administrator,ModelProfileCommand command) {
        if(!administrator.admin()) throw new ScopeViolationException();
        if(command==null || command.name()==null || command.name().isBlank() || command.name().length()>200
            || command.model()==null || command.model().isBlank() || command.model().length()>200
            || command.inputTokenBound()<1 || command.inputTokenBound()>2_000_000 || command.maxOutputTokens()<1 || command.maxOutputTokens()>32768
            || command.maxRequestBytes()<1 || command.maxRequestBytes()>256*1024 || command.maxConcurrent()<1 || command.maxConcurrent()>8
            || command.boundReference()==null || command.boundReference().isBlank() || command.boundReference().length()>1000)
            throw new IllegalArgumentException("A verified provider input-token bound is required");
        new PriceVersion(java.util.UUID.randomUUID(),command.inputRate(),command.outputRate(),command.denominator()).credits(command.inputTokenBound(),command.maxOutputTokens());
        if(command.billingUnit()!=null && !java.util.Set.of("CREDITS","CNY_MICRO").contains(command.billingUnit())) throw new IllegalArgumentException("Unsupported billing unit");
        return repository.create(administrator.userId(),command);
    }
    public ModelProfile connect(WorkspaceActor actor,ModelConnection input,boolean platform) {
        if(actor==null || platform && !actor.admin()) throw new ScopeViolationException();
        var command=ModelConnectionDefaults.resolve(input);
        if(platform && !ModelConnectionDefaults.officialEndpoint(command.endpoint())) throw new IllegalArgumentException("Platform tariffs require the official provider endpoint");
        return repository.connect(actor,command,platform);
    }
    public List<ModelProfile> list(WorkspaceActor actor) { if(actor==null) throw new ScopeViolationException(); return repository.visible(actor); }
    public void disableOwn(WorkspaceActor actor,UUID id) {
        var model=repository.require(id);
        if(model.workspaceId()==null) throw new ScopeViolationException();
        model.requireWorkspace(actor.workspaceId()); repository.disable(id,"OWNER_DISABLED");
    }
    public void disable(WorkspaceActor admin,java.util.UUID profile,String reason) {
        if(!admin.admin()) throw new ScopeViolationException();
        if(profile==null || reason==null || reason.isBlank() || reason.length()>100) throw new IllegalArgumentException("Invalid disable reason");
        repository.require(profile).requireWorkspace(admin.workspaceId()); repository.disable(profile,reason);
    }
}
