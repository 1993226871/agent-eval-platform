package io.agenteval.agents;
import io.agenteval.identity.*;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.*;
@Repository
public class JdbcAgentRepository implements AgentRepository {
    private final JdbcClient jdbc;
    public JdbcAgentRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    @Override public AgentRecord create(WorkspaceActor actor,String name,String version) {
        UUID id=UUID.randomUUID(); jdbc.sql("insert into agents(id,workspace_id,name,version) values (?,?,?,?)").params(id,actor.workspaceId(),name,version).update();
        return new AgentRecord(id,actor.workspaceId(),name,version);
    }
    @Override public AgentRecord get(WorkspaceActor actor,UUID id) { return jdbc.sql("select * from agents where id=? and workspace_id=?").params(id,actor.workspaceId()).query(this::map).optional().orElseThrow(ScopeViolationException::new); }
    @Override public List<AgentRecord> list(WorkspaceActor actor) { return jdbc.sql("select * from agents where workspace_id=? order by created_at desc limit 100").param(actor.workspaceId()).query(this::map).list(); }
    private AgentRecord map(java.sql.ResultSet row,int index) throws java.sql.SQLException { return new AgentRecord(row.getObject("id",UUID.class),row.getObject("workspace_id",UUID.class),row.getString("name"),row.getString("version")); }
}
