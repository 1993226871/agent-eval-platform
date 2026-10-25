package io.agenteval.telemetry;
import java.util.*;
public record BatchAck(List<UUID> acknowledgedEventIds) {
    public BatchAck { acknowledgedEventIds=List.copyOf(acknowledgedEventIds); }
}
