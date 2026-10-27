package io.agenteval.model;

import io.agenteval.identity.ScopeViolationException;
import java.net.URI;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class EncryptedModelProfileRepository implements ModelProfileRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ModelSecretBox secrets;
    private final Set<String> origins;
    private static final String SELECT="select p.*,v.id price_id,v.input_rate,v.output_rate,v.denominator from model_profiles p join model_price_versions v on v.profile_id=p.id ";
    public EncryptedModelProfileRepository(JdbcClient jdbc,PlatformTransactionManager manager,ModelSecretBox secrets,Set<String> origins) {
        this.jdbc=jdbc; this.transactions=new TransactionTemplate(manager); this.secrets=secrets; this.origins=Set.copyOf(origins);
    }
    private void endpoint(String value) {
        if(value==null || value.length()>2048) throw new IllegalArgumentException("Invalid provider endpoint");
        URI uri=URI.create(value);
        if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null
            || !uri.getPath().endsWith("/chat/completions") || !(origins.contains(uri.getScheme()+"://"+uri.getRawAuthority()) || ModelConnectionDefaults.officialEndpoint(value))) throw new IllegalArgumentException("Provider endpoint not allowlisted");
    }
    public ModelProfile create(UUID administrator,ModelProfileCommand command) {
        endpoint(command.endpoint());
        if(command.apiKey()==null || !command.apiKey().matches("[A-Za-z0-9._:-]{16,4096}")) throw new IllegalArgumentException("Invalid provider credential");
        UUID id=UUID.randomUUID(),price=UUID.randomUUID(); String encrypted=secrets.encrypt(id,command.apiKey());
        return transactions.execute(status -> {
            jdbc.sql("insert into model_profiles(id,name,endpoint,encrypted_api_key,model,input_token_bound,max_output_tokens,max_request_bytes,max_concurrent,bound_reference,created_by) values(?,?,?,?,?,?,?,?,?,?,?)")
                .params(id,command.name(),command.endpoint(),encrypted,command.model(),command.inputTokenBound(),command.maxOutputTokens(),command.maxRequestBytes(),command.maxConcurrent(),command.boundReference(),administrator).update();
            jdbc.sql("insert into model_price_versions(id,profile_id,input_rate,output_rate,denominator) values(?,?,?,?,?)").params(price,id,command.inputRate(),command.outputRate(),command.denominator()).update();
              io.agenteval.administration.JdbcAuditWriter.byUser(jdbc,administrator,io.agenteval.administration.AuditAction.MODEL_CREATED,id);
            return require(id);
        });
    }
    public ModelProfile require(UUID id) { return jdbc.sql(SELECT+"where p.id=?").param(id).query(this::map).optional().orElseThrow(ScopeViolationException::new); }
    @Override public ModelProfile connect(io.agenteval.identity.WorkspaceActor actor,ModelProfileCommand command,boolean platform) {
        return transactions.execute(status -> {
            var created=create(actor.userId(),command);
            jdbc.sql("update model_profiles set workspace_id=?,billing_mode=? where id=?")
                .param(1,platform?null:actor.workspaceId(),Types.OTHER).param(2,platform?"PLATFORM":"BYOK").param(3,created.id()).update();
            return require(created.id());
        });
    }
    public List<ModelProfile> list() { return jdbc.sql(SELECT+"order by p.created_at desc,p.id desc limit 100").query(this::map).list(); }
    @Override public List<ModelProfile> visible(io.agenteval.identity.WorkspaceActor actor) {
        return jdbc.sql(SELECT+"where p.workspace_id is null or p.workspace_id=? order by p.created_at desc,p.id desc limit 100").param(actor.workspaceId()).query(this::map).list();
    }
    public void disable(UUID id,String reason) {
        transactions.executeWithoutResult(status -> {
            if(jdbc.sql("update model_profiles set enabled=false,disabled_reason=? where id=? and enabled").params(reason,id).update()==1) {
                UUID owner=jdbc.sql("select w.id from workspaces w join model_profiles p on p.created_by=w.owner_user_id where p.id=?").param(id).query(UUID.class).single();
                io.agenteval.administration.JdbcAuditWriter.system(jdbc,owner,io.agenteval.administration.AuditAction.MODEL_DISABLED,id);
            }
        });
    }
    public String credential(UUID id) {
        var profile=require(id); endpoint(profile.endpoint());
        return secrets.decrypt(id,jdbc.sql("select encrypted_api_key from model_profiles where id=?").param(id).query(String.class).single());
    }
    public void checkEndpoint(ModelProfile profile) { endpoint(profile.endpoint()); }
    private ModelProfile map(ResultSet row,int index) throws SQLException {
        return new ModelProfile(row.getObject("id",UUID.class),row.getString("name"),row.getString("endpoint"),row.getString("model"),row.getLong("input_token_bound"),row.getInt("max_output_tokens"),row.getInt("max_request_bytes"),row.getInt("max_concurrent"),row.getString("bound_reference"),row.getBoolean("enabled"),row.getString("disabled_reason"),new PriceVersion(row.getObject("price_id",UUID.class),row.getLong("input_rate"),row.getLong("output_rate"),row.getLong("denominator")),row.getObject("workspace_id",UUID.class),row.getString("billing_mode"));
    }
}
