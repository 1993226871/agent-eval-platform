package io.agenteval.model;
import java.util.*;
public interface ModelProfileRepository {
    ModelProfile create(UUID administrator,ModelProfileCommand command);
    ModelProfile require(UUID id);
    List<ModelProfile> list();
    void disable(UUID id,String reason);
}
