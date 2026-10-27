package io.agenteval.quota;
import java.util.UUID;
public record QuotaWallet(UUID workspaceId,long granted,long consumed,long reserved) {
    public QuotaWallet {
        if(workspaceId==null || granted<0 || consumed<0 || reserved<0 || consumed>granted || reserved>granted-consumed)
            throw new IllegalArgumentException("Invalid quota wallet");
    }
    public long available() { return granted-consumed-reserved; }
}
