package io.agenteval.administration;

import io.agenteval.identity.WorkspaceActor;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditRepository implements AuditService.Repository {
    private final JdbcClient jdbc;
    public JdbcAuditRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public void append(WorkspaceActor actor,AuditAction action,UUID entity) { JdbcAuditWriter.append(jdbc,actor,action,entity); }
    public AuditService.Page list(UUID workspace,String cursor,int limit) {
        Instant before=null; UUID last=null;
        if(cursor!=null) try {
            if(cursor.length()>512) throw new IllegalArgumentException();
            var fields=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(fields.length!=3 || !fields[0].equals(workspace.toString())) throw new IllegalArgumentException();
            before=Instant.parse(fields[1]); last=UUID.fromString(fields[2]);
        } catch(RuntimeException invalid) { throw new IllegalArgumentException("Invalid audit cursor"); }
        var query=jdbc.sql("select * from audit_events where workspace_id=?"+(before==null?"":" and (created_at,id)<(?,?)")+" order by created_at desc,id desc limit ?");
        query.param(workspace); if(before!=null) query.param(Timestamp.from(before)).param(last); query.param(limit+1);
        var rows=query.query((row,n) -> new AuditEvent(row.getObject("id",UUID.class),workspace,row.getObject("actor_user_id",UUID.class),row.getString("action"),row.getObject("entity_id",UUID.class),row.getString("source"),row.getTimestamp("created_at").toInstant())).list();
        boolean more=rows.size()>limit; var items=rows.subList(0,Math.min(limit,rows.size())); String next=null;
        if(more) { var tail=items.getLast(); next=Base64.getUrlEncoder().withoutPadding().encodeToString((workspace+"|"+tail.createdAt()+"|"+tail.id()).getBytes(StandardCharsets.UTF_8)); }
        return new AuditService.Page(items,next);
    }
}
