package io.agenteval.administration;

import io.agenteval.identity.WorkspaceActor;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** IDs, action and attribution only: this API cannot accept prompts, keys, passwords, resource names or request bodies. */
public final class JdbcAuditWriter {
    private JdbcAuditWriter() {}
    public static void append(JdbcClient jdbc,WorkspaceActor actor,AuditAction action,UUID entity) {
        jdbc.sql("insert into audit_events(id,workspace_id,actor_user_id,action,entity_id,source) values(?,?,?,?,?,'APPLICATION')")
            .params(UUID.randomUUID(),actor.workspaceId(),actor.userId(),action.name(),entity).update();
    }
    public static void system(JdbcClient jdbc,UUID workspace,AuditAction action,UUID entity) {
        jdbc.sql("insert into audit_events(id,workspace_id,action,entity_id,source) values(?,?,?,?,'SYSTEM')").params(UUID.randomUUID(),workspace,action.name(),entity).update();
    }
    public static void byUser(JdbcClient jdbc,UUID user,AuditAction action,UUID entity) {
        UUID workspace=jdbc.sql("select id from workspaces where owner_user_id=?").param(user).query(UUID.class).single();
        append(jdbc,new WorkspaceActor(user,workspace,true),action,entity);
    }
}
