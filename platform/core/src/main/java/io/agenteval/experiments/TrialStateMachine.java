package io.agenteval.experiments;
import io.agenteval.protocol.TrialExecutionStatus;
import static io.agenteval.protocol.TrialExecutionStatus.*;
import java.util.Set;
public final class TrialStateMachine {
    public boolean canTransition(TrialExecutionStatus from,TrialExecutionStatus to) {
        return switch(from) {
            case QUEUED -> Set.of(LEASED,CANCELLED,TIMED_OUT).contains(to);
            case LEASED -> Set.of(RUNNING,CANCELLED,TIMED_OUT,EXECUTION_UNKNOWN).contains(to);
            case RUNNING -> Set.of(SUCCEEDED,FAILED,CANCELLED,TIMED_OUT,EXECUTION_UNKNOWN).contains(to);
            default -> false;
        };
    }
}
