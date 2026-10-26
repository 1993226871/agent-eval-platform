package io.agenteval.model;
import java.util.Set;
public final class ModelCallStateMachine {
    private ModelCallStateMachine() {}
    public static boolean waiting(String state) { return Set.of("WAITING_QUOTA","WAITING_CAPACITY").contains(state); }
    public static void require(String state,String next) {
        boolean allowed=switch(state) {
            case "WAITING_QUOTA","WAITING_CAPACITY" -> Set.of("RESERVED","WAITING_QUOTA","WAITING_CAPACITY","MODEL_DISABLED").contains(next);
            case "RESERVED" -> Set.of("SEND_INTENT","FAILED_UNSENT").contains(next);
            case "SEND_INTENT" -> Set.of("SUCCEEDED","PENDING_RECONCILIATION").contains(next);
            case "PENDING_RECONCILIATION" -> next.equals("RECONCILED");
            default -> false;
        };
        if(!allowed) throw new ModelCallConflictException();
    }
}
