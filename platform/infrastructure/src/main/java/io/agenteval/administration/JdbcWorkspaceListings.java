package io.agenteval.administration;

import io.agenteval.catalog.ResourceKind;
import io.agenteval.identity.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkspaceListings implements WorkspaceListings {
    private final JdbcClient jdbc;
    public JdbcWorkspaceListings(JdbcClient jdbc) { this.jdbc=jdbc; }
    @Override public List<ResourceSummary> catalog(WorkspaceActor actor,ResourceKind kind,int offset,int limit) {
        WorkspaceListings.page(offset,limit);
        return jdbc.sql("select id,kind,name,revision,archived from catalog_resources where workspace_id=? and kind=? and not archived order by created_at,id limit ? offset ?")
            .params(actor.workspaceId(),kind.name(),limit,offset).query((r,n) -> new ResourceSummary(r.getObject("id",UUID.class),ResourceKind.valueOf(r.getString("kind")),r.getString("name"),r.getLong("revision"),r.getBoolean("archived"))).list();
    }
    private static final String COLUMNS="id,workspace_id,agent_id,agent_version,dataset_version_id,suite_version_id,repeat_count,deadline,status,(snapshot->>'source_experiment_id')::uuid source_experiment_id";
    @Override public List<ExperimentSummary> experiments(WorkspaceActor actor,int offset,int limit) {
        WorkspaceListings.page(offset,limit);
        return jdbc.sql("select "+COLUMNS+" from experiments where workspace_id=? and status not in ('DELETING','DELETED') order by created_at desc,id limit ? offset ?").params(actor.workspaceId(),limit,offset).query(this::map).list();
    }
    @Override public ExperimentSummary experiment(WorkspaceActor actor,UUID id) {
        return jdbc.sql("select "+COLUMNS+" from experiments where id=? and workspace_id=? and status not in ('DELETING','DELETED')").params(id,actor.workspaceId()).query(this::map).optional().orElseThrow(ScopeViolationException::new);
    }
    private ExperimentSummary map(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new ExperimentSummary(r.getObject("id",UUID.class),r.getObject("workspace_id",UUID.class),r.getObject("agent_id",UUID.class),r.getString("agent_version"),r.getObject("dataset_version_id",UUID.class),r.getObject("suite_version_id",UUID.class),r.getInt("repeat_count"),r.getTimestamp("deadline").toInstant(),r.getString("status"),r.getObject("source_experiment_id",UUID.class));
    }
}
