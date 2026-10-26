package io.agenteval.model;

import io.agenteval.administration.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Offline maintenance only: stop writers and retain a backup with its old key before invoking. */
public final class ModelKeyRotation {
    private ModelKeyRotation() {}
    public static int rotate(DataSource source,byte[] oldKey,byte[] newKey) {
        if(oldKey==null || newKey==null || Arrays.equals(oldKey,newKey)) throw new IllegalArgumentException("Distinct old and new keys required");
        var previous=new ModelSecretBox(oldKey); var next=new ModelSecretBox(newKey);
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var jdbc=JdbcClient.create(new SingleConnectionDataSource(connection,true));
                var profiles=jdbc.sql("select p.id,p.encrypted_api_key,w.id workspace from model_profiles p join workspaces w on w.owner_user_id=p.created_by order by p.id for update of p")
                    .query((r,n) -> new Credential(r.getObject("id",UUID.class),r.getObject("workspace",UUID.class),r.getString("encrypted_api_key"))).list();
                for(var profile:profiles) {
                    String encrypted=next.encrypt(profile.id(),previous.decrypt(profile.id(),profile.encrypted()));
                    jdbc.sql("update model_profiles set encrypted_api_key=? where id=?").params(encrypted,profile.id()).update();
                    JdbcAuditWriter.system(jdbc,profile.workspace(),AuditAction.MODEL_MASTER_KEY_ROTATED,profile.id());
                }
                connection.commit(); return profiles.size();
            } catch(RuntimeException | java.sql.SQLException failure) { connection.rollback(); throw failure; }
        } catch(java.sql.SQLException unavailable) { throw new IllegalStateException("Offline model key rotation failed"); }
    }
    private record Credential(UUID id,UUID workspace,String encrypted) { @Override public String toString() { return "Credential[redacted]"; } }
}
