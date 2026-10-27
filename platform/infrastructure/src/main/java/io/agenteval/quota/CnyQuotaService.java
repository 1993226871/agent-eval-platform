package io.agenteval.quota;

import io.agenteval.identity.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CnyQuotaService {
    private final QuotaService quota;
    public CnyQuotaService(QuotaService quota) { this.quota=quota; }
    @Transactional public void openNewAccount(WorkspaceActor admin,UUID workspace) {
        quota.grant(admin,workspace,5_000_000,"新用户平台评审额度 ¥5.00",workspace);
    }
    @Transactional public QuotaWallet adjust(WorkspaceActor admin,UUID workspace,String amount,String reason,UUID key) {
        return quota.grant(admin,workspace,CnyAmount.parse(amount),reason,key);
    }
}
