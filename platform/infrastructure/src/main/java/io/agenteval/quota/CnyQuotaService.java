package io.agenteval.quota;

import io.agenteval.identity.*;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CnyQuotaService {
    private final JdbcClient jdbc;
    private final QuotaService quota;
    public CnyQuotaService(JdbcClient jdbc,QuotaService quota) { this.jdbc=jdbc; this.quota=quota; }
    private void initialize(WorkspaceActor admin,UUID workspace) {
        if(!admin.admin()) throw new ScopeViolationException();
        String unit=jdbc.sql("select unit from quota_wallets where workspace_id=? for update").param(workspace).query(String.class).optional().orElseThrow(ScopeViolationException::new);
        if(unit.equals("CNY_MICRO")) return;
        // Never relabel historical credits as money. Only an empty, unused wallet can opt in.
        int changed=jdbc.sql("update quota_wallets set unit='CNY_MICRO' where workspace_id=? and granted=0 and consumed=0 and reserved=0 and not exists(select 1 from quota_ledger where workspace_id=?)").params(workspace,workspace).update();
        if(changed!=1) throw new IllegalArgumentException("Legacy credit history cannot be converted automatically to CNY");
    }
    @Transactional public void openNewAccount(WorkspaceActor admin,UUID workspace) {
        initialize(admin,workspace);
        quota.grant(admin,workspace,5_000_000,"新用户平台评审额度 ¥5.00",workspace);
    }
    @Transactional public QuotaWallet adjust(WorkspaceActor admin,UUID workspace,String amount,String reason,UUID key) {
        initialize(admin,workspace);
        return quota.grant(admin,workspace,CnyAmount.parse(amount),reason,key);
    }
}
