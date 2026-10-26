package io.agenteval.quota;
import java.util.*;
public interface QuotaRepository {
    QuotaWallet wallet(UUID workspaceId);
    List<QuotaEntry> entries(UUID workspaceId,int limit);
    QuotaLedgerPage page(UUID workspaceId,String cursor,int limit);
    QuotaWallet adjust(UUID workspaceId,long credits,String reason,UUID key,UUID adminId);
    Reservation reserve(UUID workspaceId,UUID callId,long upperCredits);
    Reservation transition(UUID callId,String operation,Long actualCredits);
    Reservation approveOverage(UUID callId,long actualCredits,UUID administrator,String reason,UUID key);
}
