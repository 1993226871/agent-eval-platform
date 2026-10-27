package io.agenteval.model;

import io.agenteval.identity.WorkspaceActor;
import io.agenteval.protocol.ProtocolCodec;
import io.agenteval.quota.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Short transactions own slots and reservations. No provider HTTP occurs under a DB lock. */
public final class JdbcModelCallRepository implements ModelCallRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ModelProfileRepository profiles;
    private final QuotaService quota;
    private final ProtocolCodec codec=new ProtocolCodec();
    private static final String ACTIVE="('RESERVED','SEND_INTENT','PENDING_RECONCILIATION')";
    public JdbcModelCallRepository(JdbcClient jdbc,PlatformTransactionManager manager,ModelProfileRepository profiles,QuotaService quota) {
        this.jdbc=jdbc; transactions=new TransactionTemplate(manager); this.profiles=profiles; this.quota=quota;
    }
    private <T> T locked(Supplier<T> action) {
        return transactions.execute(status -> { jdbc.sql("select id from model_gateway_guard where id=1 for update").query(Integer.class).single(); return action.get(); });
    }
    private record Stored(JudgeRequest request,String hash,String state,long upper,UUID token,Instant deadline,JudgeResponse response,UUID jobId) {}
    private Optional<Stored> find(UUID callId) {
        return jdbc.sql("select * from model_calls where call_id=?").param(callId).query((row,index) -> new Stored(decode(row.getString("request_json"),JudgeRequest.class),row.getString("request_hash"),row.getString("state"),row.getLong("upper_credits"),row.getObject("dispatch_token",UUID.class),row.getTimestamp("dispatch_deadline")==null?null:row.getTimestamp("dispatch_deadline").toInstant(),decode(row.getString("response_json"),JudgeResponse.class),row.getObject("evaluation_job_id",UUID.class))).optional();
    }
    public Preparation prepare(JudgeRequest request,Instant now,UUID evaluationJobId) {
        String body=encode(request),hash=hash(body);
        return locked(() -> {
            var previous=find(request.callId());
            if(previous.isPresent()) {
                var prior=previous.get();
                if(!prior.hash().equals(hash) || !prior.request().workspaceId().equals(request.workspaceId()) || !Objects.equals(prior.jobId(),evaluationJobId)) throw new ModelCallConflictException();
                if(!ModelCallStateMachine.waiting(prior.state())) return new Preparation(prior.response(),null);
            }
            if(!evaluationActive(evaluationJobId,request.workspaceId(),now)) {
                var cancelled=empty(request,"CANCELLED","EVALUATION_NOT_ACTIVE");
                if(previous.isPresent()) update(previous.get(),"CANCELLED",cancelled,now);
                return new Preparation(cancelled,null);
            }
            var profile=profiles.require(request.modelProfileId());
            profile.requireWorkspace(request.workspaceId());
            if(!profile.price().id().equals(request.priceVersionId()) || request.maxOutputTokens()>profile.maxOutputTokens() || body.getBytes(StandardCharsets.UTF_8).length>profile.maxRequestBytes()) throw new IllegalArgumentException("Model request exceeds fixed profile");
            long upper=profile.usesPlatformQuota()?profile.price().credits(profile.inputTokenBound(),request.maxOutputTokens()):0;
            if(profile.usesPlatformQuota() && upper<=0) throw new IllegalArgumentException("Invalid model reservation bound");
            String state;
            UUID token=null; Instant deadline=null;
            if(!profile.enabled()) state="MODEL_DISABLED";
            else if(jdbc.sql("select count(*) from model_calls where state in "+ACTIVE).query(Long.class).single()>=8
                || jdbc.sql("select count(*) from model_calls where workspace_id=? and state in "+ACTIVE).param(request.workspaceId()).query(Long.class).single()>=2
                || jdbc.sql("select count(*) from model_calls where profile_id=? and state in "+ACTIVE).param(profile.id()).query(Long.class).single()>=profile.maxConcurrent()) state="WAITING_CAPACITY";
            else {
                // Balance pre-check avoids catching a rollback-only nested reservation transaction.
                if(!profile.usesPlatformQuota()) { state="RESERVED"; token=UUID.randomUUID(); deadline=now.plusSeconds(90); }
                else {
                    long available=jdbc.sql("select granted-consumed-reserved from quota_wallets where workspace_id=? for update").param(request.workspaceId()).query(Long.class).single();
                    if(available<upper) state="WAITING_QUOTA";
                    else { quota.reserve(request.workspaceId(),request.callId(),upper); state="RESERVED"; token=UUID.randomUUID(); deadline=now.plusSeconds(90); }
                }
            }
            var response=empty(request,state,state.equals("MODEL_DISABLED")?"MODEL_DISABLED":null);
            if(previous.isEmpty()) jdbc.sql("insert into model_calls(call_id,workspace_id,profile_id,price_version_id,request_hash,request_json,state,upper_credits,dispatch_token,dispatch_deadline,response_json,created_at,updated_at,evaluation_job_id) values(?,?,?,?,?,cast(? as jsonb),?,?,?,?,cast(? as jsonb),?,?,?)")
                .param(1,request.callId()).param(2,request.workspaceId()).param(3,profile.id()).param(4,profile.price().id()).param(5,hash).param(6,body).param(7,state).param(8,upper).param(9,token,Types.OTHER).param(10,deadline==null?null:deadline.atOffset(java.time.ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE).param(11,encode(response)).param(12,Timestamp.from(now)).param(13,Timestamp.from(now)).param(14,evaluationJobId,Types.OTHER).update();
            else {
                ModelCallStateMachine.require(previous.get().state(),state);
                jdbc.sql("update model_calls set state=?,dispatch_token=?,dispatch_deadline=?,response_json=cast(? as jsonb),updated_at=? where call_id=?")
                    .param(1,state).param(2,token,Types.OTHER).param(3,deadline==null?null:deadline.atOffset(java.time.ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE).param(4,encode(response)).param(5,Timestamp.from(now)).param(6,request.callId()).update();
            }
            return new Preparation(response,state.equals("RESERVED")?new ModelDispatch(request,profile,token,deadline,upper,evaluationJobId):null);
        });
    }
    private Stored requireDispatch(ModelDispatch dispatch,Instant now,String state) {
        var stored=find(dispatch.request().callId()).orElseThrow(ModelCallConflictException::new);
        if(!stored.state().equals(state) || !Objects.equals(stored.token(),dispatch.token()) || !Objects.equals(stored.jobId(),dispatch.evaluationJobId()) || !stored.hash().equals(hash(encode(dispatch.request()))) || stored.deadline()==null || !now.isBefore(stored.deadline())) throw new ModelCallConflictException();
        return stored;
    }
    public JudgeResponse markSendIntent(ModelDispatch dispatch,Instant now) {
        return locked(() -> {
            var stored=requireDispatch(dispatch,now,"RESERVED");
            if(!evaluationActive(stored.jobId(),stored.request().workspaceId(),now)) {
                if(stored.upper()>0) quota.releaseUnsent(stored.request().callId()); var rejected=empty(stored.request(),"FAILED_UNSENT","EVALUATION_CANCELLED_BEFORE_SEND"); update(stored,"FAILED_UNSENT",rejected,now); return rejected;
            }
            if(stored.upper()>0) quota.markSendIntent(stored.request().callId()); update(stored,"SEND_INTENT",empty(stored.request(),"SEND_INTENT",null),now); return null;
        });
    }
    private boolean evaluationActive(UUID jobId,UUID workspace,Instant now) {
        if(jobId==null) return true; // Internal administrator connectivity checks have no evaluation job.
        jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single();
        return jdbc.sql("select count(*) from evaluation_jobs j join trials t on t.id=j.trial_id join experiments e on e.id=t.experiment_id where j.id=? and j.workspace_id=? and j.status='RUNNING' and j.lease_expires_at>? and t.status<>'CANCELLED' and e.status not in ('CANCELLED','DELETING','DELETED')").params(jobId,workspace,Timestamp.from(now)).query(Long.class).single()==1;
    }
    public JudgeResponse failUnsent(ModelDispatch dispatch,Instant now) {
        return locked(() -> { var stored=requireDispatch(dispatch,now,"RESERVED"); if(stored.upper()>0) quota.releaseUnsent(stored.request().callId()); var result=empty(stored.request(),"FAILED_UNSENT","PROVIDER_PREPARATION_FAILED"); update(stored,"FAILED_UNSENT",result,now); return result; });
    }
    public JudgeResponse uncertain(ModelDispatch dispatch,Instant now) {
        return locked(() -> { var stored=requireDispatch(dispatch,now,"SEND_INTENT"); return pending(stored,null,"PROVIDER_USAGE_UNCERTAIN",now); });
    }
    public JudgeResponse complete(ModelDispatch dispatch,ProviderResponse response,Instant now) {
        return locked(() -> {
            var stored=requireDispatch(dispatch,now,"SEND_INTENT"); var request=stored.request();
            if(stored.upper()==0) {
                var result=respond(request,"SUCCEEDED",response,0L,null); update(stored,"SUCCEEDED",result,now); return result;
            }
            if(response.inputTokens()==null || response.outputTokens()==null) return pending(stored,response,"PROVIDER_USAGE_MISSING",now);
            long credits;
            try { credits=dispatch.profile().price().credits(response.inputTokens(),response.outputTokens()); }
            catch(IllegalArgumentException overflow) { profiles.disable(request.modelProfileId(),"USAGE_BOUND_VIOLATION"); return pending(stored,response,"USAGE_CALCULATION_OVERFLOW",now); }
            if(response.inputTokens()>dispatch.profile().inputTokenBound() || response.outputTokens()>request.maxOutputTokens() || credits>stored.upper()) {
                profiles.disable(request.modelProfileId(),"USAGE_BOUND_VIOLATION"); return pending(stored,response,"USAGE_BOUND_VIOLATION",now);
            }
            quota.settle(request.callId(),credits);
            var result=respond(request,"SUCCEEDED",response,credits,null); update(stored,"SUCCEEDED",result,now); return result;
        });
    }
    private JudgeResponse pending(Stored stored,ProviderResponse response,String error,Instant now) {
        if(stored.upper()>0) quota.markReconciliation(stored.request().callId());
        var result=respond(stored.request(),"PENDING_RECONCILIATION",response,null,error); update(stored,"PENDING_RECONCILIATION",result,now); return result;
    }
    private void update(Stored stored,String state,JudgeResponse response,Instant now) {
        ModelCallStateMachine.require(stored.state(),state);
        jdbc.sql("update model_calls set state=?,response_json=cast(? as jsonb),updated_at=? where call_id=?").params(state,encode(response),Timestamp.from(now),stored.request().callId()).update();
    }
    public void recover(Instant now) {
        locked(() -> {
            var ids=jdbc.sql("select call_id from model_calls where state in ('RESERVED','SEND_INTENT') and dispatch_deadline<=? order by dispatch_deadline limit 100").param(Timestamp.from(now)).query(UUID.class).list();
            for(var id:ids) {
                var stored=find(id).orElseThrow();
                if(stored.state().equals("RESERVED")) { if(stored.upper()>0) quota.releaseUnsent(id); update(stored,"FAILED_UNSENT",empty(stored.request(),"FAILED_UNSENT","PRE_SEND_PROCESS_LOST"),now); }
                else pending(stored,null,"POST_SEND_PROCESS_LOST",now);
            }
            return null;
        });
    }
    public List<ModelCallSummary> pending(int limit) {
        return jdbc.sql("select call_id,workspace_id,profile_id,price_version_id,state,upper_credits,response_json->>'input_tokens' input_tokens,response_json->>'output_tokens' output_tokens,response_json->>'reported_credits' reported_credits,response_json->>'failure_code' failure_code,created_at from model_calls where state='PENDING_RECONCILIATION' order by created_at,call_id limit ?")
            .param(limit).query((row,index)->new ModelCallSummary(row.getObject("call_id",UUID.class),row.getObject("workspace_id",UUID.class),row.getObject("profile_id",UUID.class),row.getObject("price_version_id",UUID.class),row.getString("state"),row.getLong("upper_credits"),row.getString("input_tokens")==null?null:Long.valueOf(row.getString("input_tokens")),row.getString("output_tokens")==null?null:Long.valueOf(row.getString("output_tokens")),row.getString("reported_credits")==null?null:Long.valueOf(row.getString("reported_credits")),row.getString("failure_code"),row.getTimestamp("created_at").toInstant())).list();
    }
    public JudgeResponse reconcile(WorkspaceActor admin,UUID callId,long input,long output,String reason,UUID key,Instant now) {
        return locked(() -> {
            var stored=find(callId).orElseThrow(ModelCallConflictException::new);
            var prior=jdbc.sql("select call_id,administrator,input_tokens,output_tokens,reason from model_reconciliations where idempotency_key=? or call_id=?").params(key,callId).query((row,index) -> Map.of("call",row.getObject("call_id",UUID.class),"admin",row.getObject("administrator",UUID.class),"input",row.getLong("input_tokens"),"output",row.getLong("output_tokens"),"reason",row.getString("reason"))).list();
            if(prior.size()>1) throw new ModelCallConflictException();
            if(!prior.isEmpty()) {
                var data=prior.getFirst();
                if(!data.get("call").equals(callId) || !data.get("admin").equals(admin.userId()) || !data.get("input").equals(input) || !data.get("output").equals(output) || !data.get("reason").equals(reason)
                    || jdbc.sql("select count(*) from model_reconciliations where idempotency_key=? and call_id=?").params(key,callId).query(Long.class).single()!=1) throw new ModelCallConflictException();
                return stored.response();
            }
            if(!stored.state().equals("PENDING_RECONCILIATION")) throw new ModelCallConflictException();
            long credits=stored.upper()==0?0:profiles.require(stored.request().modelProfileId()).price().credits(input,output);
            if(credits>stored.upper()) quota.approveOverage(admin,callId,credits,reason,key);
            if(stored.upper()>0) quota.settle(callId,credits);
            jdbc.sql("insert into model_reconciliations(idempotency_key,call_id,administrator,input_tokens,output_tokens,credits,reason,created_at) values(?,?,?,?,?,?,?,?)").params(key,callId,admin.userId(),input,output,credits,reason,Timestamp.from(now)).update();
            io.agenteval.administration.JdbcAuditWriter.append(jdbc,new WorkspaceActor(admin.userId(),stored.request().workspaceId(),true),io.agenteval.administration.AuditAction.MODEL_RECONCILED,callId);
            var previous=stored.response();
            var result=new JudgeResponse(callId,"RECONCILED",previous.content(),input,output,credits,previous.profileId(),previous.priceVersionId(),previous.providerModel(),previous.finishReason(),previous.providerResponseId(),"ADMINISTRATOR_RECONCILED",previous.rawResponse(),credits);
            update(stored,"RECONCILED",result,now); return result;
        });
    }
    private JudgeResponse empty(JudgeRequest request,String state,String error) { return respond(request,state,null,null,error); }
    private JudgeResponse respond(JudgeRequest request,String state,ProviderResponse response,Long credits,String error) {
        Long reported=null;
        if(response!=null && response.inputTokens()!=null && response.outputTokens()!=null) {
            try { var profile=profiles.require(request.modelProfileId()); reported=profile.usesPlatformQuota()?profile.price().credits(response.inputTokens(),response.outputTokens()):0L; }
            catch(IllegalArgumentException overflow) { /* Usage and frozen price remain available for manual investigation. */ }
        }
        return new JudgeResponse(request.callId(),state,response==null?null:response.content(),response==null?null:response.inputTokens(),response==null?null:response.outputTokens(),credits,request.modelProfileId(),request.priceVersionId(),response==null?null:response.model(),response==null?null:response.finishReason(),response==null?null:response.responseId(),error,response==null?null:response.rawResponse(),reported);
    }
    private String encode(Object value) { try { return codec.encode(value); } catch(java.io.IOException invalid) { throw new IllegalArgumentException("Invalid model JSON"); } }
    private <T> T decode(String value,Class<T> type) { try { return codec.decode(value,type); } catch(java.io.IOException invalid) { throw new IllegalArgumentException("Invalid persisted model JSON"); } }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
