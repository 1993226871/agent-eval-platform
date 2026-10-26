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
            || !uri.getPath().endsWith("/chat/completions") || !origins.contains(uri.getScheme()+"://"+uri.getRawAuthority())) throw new IllegalArgumentException("Provider endpoint not allowlisted");
    }
    public ModelProfile create(UUID administrator,ModelProfileCommand command) {
        endpoint(command.endpoint());
        if(command.apiKey()==null || !command.apiKey().matches("[A-Za-z0-9._:-]{16,4096}")) throw new IllegalArgumentException("Invalid provider credential");
        UUID id=UUID.randomUUID(),price=UUID.randomUUID(); String encrypted=secrets.encrypt(id,command.apiKey());
        return transactions.execute(status -> {
            jdbc.sql("insert into model_profiles(id,name,endpoint,encrypted_api_key,model,input_token_bound,max_output_tokens,max_request_bytes,max_concurrent,bound_reference,created_by) values(?,?,?,?,?,?,?,?,?,?,?)")
                .params(id,command.name(),command.endpoint(),encrypted,command.model(),command.inputTokenBound(),command.maxOutputTokens(),command.maxRequestBytes(),command.maxConcurrent(),command.boundReference(),administrator).update();
            jdbc.sql("insert into model_price_versions(id,profile_id,input_rate,output_rate,denominator) values(?,?,?,?,?)").params(price,id,command.inputRate(),command.outputRate(),command.denominator()).update();
            return require(id);
        });
    }
    public ModelProfile require(UUID id) { return jdbc.sql(SELECT+"where p.id=?").param(id).query(this::map).optional().orElseThrow(ScopeViolationException::new); }
    public List<ModelProfile> list() { return jdbc.sql(SELECT+"order by p.created_at desc,p.id desc limit 100").query(this::map).list(); }
    public void disable(UUID id,String reason) { jdbc.sql("update model_profiles set enabled=false,disabled_reason=? where id=?").params(reason,id).update(); }
    public String credential(UUID id) {
        var profile=require(id); endpoint(profile.endpoint());
        return secrets.decrypt(id,jdbc.sql("select encrypted_api_key from model_profiles where id=?").param(id).query(String.class).single());
    }
    public void checkEndpoint(ModelProfile profile) { endpoint(profile.endpoint()); }
    private ModelProfile map(ResultSet row,int index) throws SQLException {
        return new ModelProfile(row.getObject("id",UUID.class),row.getString("name"),row.getString("endpoint"),row.getString("model"),row.getLong("input_token_bound"),row.getInt("max_output_tokens"),row.getInt("max_request_bytes"),row.getInt("max_concurrent"),row.getString("bound_reference"),row.getBoolean("enabled"),row.getString("disabled_reason"),new PriceVersion(row.getObject("price_id",UUID.class),row.getLong("input_rate"),row.getLong("output_rate"),row.getLong("denominator")));
    }
}
