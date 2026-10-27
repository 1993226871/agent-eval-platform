package io.agenteval.model;
import java.util.*;
public interface ModelProfileRepository {
    ModelProfile create(UUID administrator,ModelProfileCommand command);
    default ModelProfile connect(io.agenteval.identity.WorkspaceActor actor,ModelProfileCommand command,boolean platform) { throw new UnsupportedOperationException(); }
    ModelProfile require(UUID id);
    List<ModelProfile> list();
    default List<ModelProfile> visible(io.agenteval.identity.WorkspaceActor actor) { return list().stream().filter(item->item.visibleTo(actor)).toList(); }
    void disable(UUID id,String reason);
}
