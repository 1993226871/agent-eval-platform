package io.agenteval.quota;
import io.agenteval.identity.WorkspaceActor;

import io.agenteval.identity.ScopeViolationException;
import java.sql.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcQuotaRepository implements QuotaRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    public JdbcQuotaRepository(JdbcClient jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc; transactions=new TransactionTemplate(manager); }
    public QuotaWallet wallet(UUID workspaceId) { return loadWallet(workspaceId,false); }
    private QuotaWallet loadWallet(UUID workspaceId,boolean lock) {
        return jdbc.sql("select * from quota_wallets where workspace_id=?"+(lock?" for update":"")).param(workspaceId)
            .query((row,index)->new QuotaWallet(row.getObject("workspace_id",UUID.class),row.getLong("granted"),row.getLong("consumed"),row.getLong("reserved"))).optional().orElseThrow(ScopeViolationException::new);
    }
    public List<QuotaEntry> entries(UUID workspaceId,int limit) {
        return jdbc.sql("select * from quota_ledger where workspace_id=? order by created_at desc,id desc limit ?").params(workspaceId,limit).query(this::entry).list();
    }
    public QuotaLedgerPage page(UUID workspace,String cursor,int limit) {
        java.time.Instant before=null; UUID last=null;
        if(cursor!=null) try {
            if(cursor.length()>512) throw new IllegalArgumentException();
            var fields=new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);
            if(fields.length!=4 || !fields[0].equals("quota-ledger") || !fields[1].equals(workspace.toString())) throw new IllegalArgumentException();
            before=java.time.Instant.parse(fields[2]); last=UUID.fromString(fields[3]);
        } catch(RuntimeException invalid) { throw new IllegalArgumentException("Invalid ledger cursor"); }
        var query=jdbc.sql("select * from quota_ledger where workspace_id=?"+(before==null?"":" and (created_at,id)<(?,?)")+" order by created_at desc,id desc limit ?");
        query.param(workspace); if(before!=null) query.param(Timestamp.from(before)).param(last); query.param(limit+1);
        var rows=query.query(this::entry).list(); boolean more=rows.size()>limit; var items=rows.subList(0,Math.min(limit,rows.size())); String next=null;
        if(more) { var tail=items.getLast(); next=Base64.getUrlEncoder().withoutPadding().encodeToString(("quota-ledger|"+workspace+"|"+tail.createdAt()+"|"+tail.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        return new QuotaLedgerPage(items,next);
    }
    private QuotaEntry entry(ResultSet row,int index) throws SQLException {
        return new QuotaEntry(row.getObject("id",UUID.class),row.getObject("call_id",UUID.class),row.getString("entry_type"),row.getLong("granted_delta"),row.getLong("consumed_delta"),row.getLong("reserved_delta"),row.getString("reason"),row.getObject("actor_user_id",UUID.class),row.getTimestamp("created_at").toInstant());
    }
    public QuotaWallet adjust(UUID workspaceId,long credits,String reason,UUID key,UUID adminId) {
        try { return transactions.execute(status -> {
            var wallet=loadWallet(workspaceId,true);
            var existing=jdbc.sql("select * from quota_ledger where id=?").param(key).query((row,index)->new Adjustment(row.getObject("workspace_id",UUID.class),entry(row,index))).optional();
            if(existing.isPresent()) {
                var prior=existing.get(); var item=prior.entry();
                if(!prior.workspace().equals(workspaceId) || !item.type().equals("ADJUST") || item.grantedDelta()!=credits || !item.reason().equals(reason) || !adminId.equals(item.actorUserId())) throw new QuotaConflictException();
                return wallet;
            }
            long granted;
            try { granted=Math.addExact(wallet.granted(),credits); }
            catch(ArithmeticException overflow) { throw new IllegalArgumentException("Quota overflow"); }
            if(granted<0 || wallet.consumed()>granted || wallet.reserved()>granted-wallet.consumed()) throw new InsufficientQuotaException();
            jdbc.sql("update quota_wallets set granted=? where workspace_id=?").params(granted,workspaceId).update();
            append(key,workspaceId,null,"ADJUST",credits,0,0,reason,adminId);
            io.agenteval.administration.JdbcAuditWriter.append(jdbc,new WorkspaceActor(adminId,workspaceId,true),io.agenteval.administration.AuditAction.QUOTA_ADJUSTED,key);
            return new QuotaWallet(workspaceId,granted,wallet.consumed(),wallet.reserved());
        }); } catch(DuplicateKeyException conflict) { throw new QuotaConflictException(); }
    }
    private record Adjustment(UUID workspace,QuotaEntry entry) {}
    public Reservation reserve(UUID workspaceId,UUID callId,long upperCredits) {
        try { return transactions.execute(status -> {
            var wallet=loadWallet(workspaceId,true);
            var prior=find(callId,false);
            if(prior.isPresent()) {
                var reservation=prior.get();
                if(!reservation.workspaceId().equals(workspaceId) || reservation.upperCredits()!=upperCredits) throw new QuotaConflictException();
                return reservation;
            }
            if(wallet.available()<upperCredits) throw new InsufficientQuotaException();
            jdbc.sql("insert into quota_reservations(call_id,workspace_id,upper_credits,state) values(?,?,?,'RESERVED')").params(callId,workspaceId,upperCredits).update();
            jdbc.sql("update quota_wallets set reserved=? where workspace_id=?").params(Math.addExact(wallet.reserved(),upperCredits),workspaceId).update();
            append(UUID.randomUUID(),workspaceId,callId,"RESERVE",0,0,upperCredits,"Reserve bounded model call",null);
            return new Reservation(callId,workspaceId,upperCredits,"RESERVED",null);
        }); } catch(DuplicateKeyException conflict) { throw new QuotaConflictException(); }
    }
    private Optional<Reservation> find(UUID callId,boolean lock) {
        return jdbc.sql("select * from quota_reservations where call_id=?"+(lock?" for update":"")).param(callId)
            .query((row,index)->new Reservation(row.getObject("call_id",UUID.class),row.getObject("workspace_id",UUID.class),row.getLong("upper_credits"),row.getString("state"),(Long)row.getObject("actual_credits"))).optional();
    }
    public Reservation transition(UUID callId,String operation,Long actual) {
        return transactions.execute(status -> {
            UUID workspace=find(callId,false).orElseThrow(QuotaConflictException::new).workspaceId();
            var wallet=loadWallet(workspace,true); // Every path uses wallet -> reservation lock order.
            var reservation=find(callId,true).orElseThrow(QuotaConflictException::new);
            String state=reservation.state(); long upper=reservation.upperCredits(); String next;
            switch(operation) {
                case "SETTLE" -> {
                    if(actual==null || actual<0 || actual>upper || state.equals("RELEASED")) throw new QuotaConflictException();
                    if(state.equals("SETTLED")) { if(!actual.equals(reservation.actualCredits())) throw new QuotaConflictException(); return reservation; }
                    jdbc.sql("update quota_wallets set consumed=?,reserved=? where workspace_id=?").params(Math.addExact(wallet.consumed(),actual),wallet.reserved()-upper,workspace).update();
                    append(UUID.randomUUID(),workspace,callId,"SETTLE",0,actual,-actual,"Settle verified model usage",null);
                    if(upper>actual) append(UUID.randomUUID(),workspace,callId,"RELEASE",0,0,-(upper-actual),"Release unused reservation",null);
                    next="SETTLED";
                }
                case "RELEASE" -> {
                    if(state.equals("RELEASED")) return reservation;
                    if(!state.equals("RESERVED")) throw new QuotaConflictException();
                    jdbc.sql("update quota_wallets set reserved=? where workspace_id=?").params(wallet.reserved()-upper,workspace).update();
                    append(UUID.randomUUID(),workspace,callId,"RELEASE",0,0,-upper,"Release definitely unsent call",null); next="RELEASED";
                }
                case "SEND_INTENT" -> {
                    if(Set.of("SEND_INTENT","RECONCILIATION","SETTLED").contains(state)) return reservation;
                    if(!state.equals("RESERVED")) throw new QuotaConflictException();
                    append(UUID.randomUUID(),workspace,callId,"SEND_INTENT",0,0,0,"Provider send intent",null); next="SEND_INTENT";
                }
                case "RECONCILIATION" -> {
                    if(Set.of("RECONCILIATION","SETTLED").contains(state)) return reservation;
                    if(!state.equals("SEND_INTENT")) throw new QuotaConflictException();
                    append(UUID.randomUUID(),workspace,callId,"RECONCILIATION",0,0,0,"Provider acceptance or usage uncertain",null); next="RECONCILIATION";
                }
                default -> throw new IllegalArgumentException("Unknown quota operation");
            }
            jdbc.sql("update quota_reservations set state=?,actual_credits=? where call_id=?").param(1,next).param(2,actual,Types.BIGINT).param(3,callId).update();
            return new Reservation(callId,workspace,upper,next,actual);
        });
    }
    public Reservation approveOverage(UUID callId,long actual,UUID administrator,String reason,UUID key) {
        return transactions.execute(status -> {
            UUID workspace=find(callId,false).orElseThrow(QuotaConflictException::new).workspaceId();
            var wallet=loadWallet(workspace,true); var reservation=find(callId,true).orElseThrow(QuotaConflictException::new);
            if(!reservation.state().equals("RECONCILIATION")) throw new QuotaConflictException();
            if(actual<=reservation.upperCredits()) return reservation;
            long extra=actual-reservation.upperCredits();
            if(wallet.available()<extra) throw new InsufficientQuotaException();
            if(jdbc.sql("select count(*) from quota_ledger where id=?").param(key).query(Long.class).single()!=0) throw new QuotaConflictException();
            jdbc.sql("update quota_wallets set reserved=? where workspace_id=?").params(Math.addExact(wallet.reserved(),extra),workspace).update();
            jdbc.sql("update quota_reservations set upper_credits=? where call_id=?").params(actual,callId).update();
            append(key,workspace,null,"ADJUST",0,0,extra,"Approved overage; reconciliation "+key+"; call "+callId,administrator);
            return new Reservation(callId,workspace,actual,reservation.state(),null);
        });
    }
    private void append(UUID id,UUID workspace,UUID call,String type,long granted,long consumed,long reserved,String reason,UUID actor) {
        jdbc.sql("insert into quota_ledger(id,workspace_id,call_id,entry_type,granted_delta,consumed_delta,reserved_delta,reason,actor_user_id) values(?,?,?,?,?,?,?,?,?)")
            .param(1,id).param(2,workspace).param(3,call,Types.OTHER).param(4,type).param(5,granted).param(6,consumed).param(7,reserved).param(8,reason).param(9,actor,Types.OTHER).update();
    }
}
