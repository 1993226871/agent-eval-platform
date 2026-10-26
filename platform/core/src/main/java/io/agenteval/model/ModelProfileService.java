package io.agenteval.model;
import io.agenteval.identity.*;
import java.util.List;
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
        return repository.create(administrator.userId(),command);
    }
    public List<ModelProfile> list(WorkspaceActor actor) { if(actor==null) throw new ScopeViolationException(); return repository.list(); }
    public void disable(WorkspaceActor admin,java.util.UUID profile,String reason) {
        if(!admin.admin()) throw new ScopeViolationException();
        if(profile==null || reason==null || reason.isBlank() || reason.length()>100) throw new IllegalArgumentException("Invalid disable reason");
        repository.require(profile); repository.disable(profile,reason);
    }
}
