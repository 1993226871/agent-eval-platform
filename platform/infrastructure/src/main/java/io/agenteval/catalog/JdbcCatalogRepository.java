package io.agenteval.catalog;
import io.agenteval.identity.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Repository
public class JdbcCatalogRepository implements CatalogRepository {
    @Override public List<PublishedVersionSummary> versions(WorkspaceActor actor,UUID resourceId,int offset,int limit) {
        return jdbc.sql("select id,number,draft_revision,content_hash from catalog_versions where resource_id=? and workspace_id=? order by number desc limit ? offset ?").params(resourceId,actor.workspaceId(),limit,offset)
            .query((row,index)->new PublishedVersionSummary(row.getObject("id",UUID.class),row.getInt("number"),row.getLong("draft_revision"),row.getString("content_hash"))).list();
    }
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final io.agenteval.administration.JdbcReadBudget readBudget;
    public JdbcCatalogRepository(JdbcClient jdbc,PlatformTransactionManager manager,ObjectMapper json) { this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.json=json; this.readBudget=new io.agenteval.administration.JdbcReadBudget(manager); }
    @Override public CatalogResource create(WorkspaceActor actor,ResourceKind kind,String name,JsonNode content) {
        return tx.execute(status -> {
            io.agenteval.administration.JdbcResourceAdmission.metadata(jdbc,actor.workspaceId(),"catalog_resources");
            UUID id=UUID.randomUUID();
            jdbc.sql("insert into catalog_resources(id,workspace_id,kind,name,content) values (?,?,?,?,cast(? as jsonb))").params(id,actor.workspaceId(),kind.name(),name,content.toString()).update();
            io.agenteval.administration.JdbcAuditWriter.append(jdbc,actor,io.agenteval.administration.AuditAction.CATALOG_CREATED,id);
            return draft(actor,id);
        });
    }
    @Override public CatalogResource draft(WorkspaceActor actor,UUID id) {
        return jdbc.sql("select * from catalog_resources where id=? and workspace_id=?").params(id,actor.workspaceId()).query(this::mapDraft).optional().orElseThrow(ScopeViolationException::new);
    }
    @Override public List<CatalogResource> list(WorkspaceActor actor,ResourceKind kind,int offset,int limit) {
        return readBudget.load(() -> jdbc.sql("select coalesce(sum(octet_length(content::text)),0) from (select content from catalog_resources where workspace_id=? and kind=? and not archived order by created_at,id limit ? offset ?) p").params(actor.workspaceId(),kind.name(),limit,offset).query(Long.class).single(),
            () -> jdbc.sql("select * from catalog_resources where workspace_id=? and kind=? and archived=false order by created_at,id limit ? offset ?").params(actor.workspaceId(),kind.name(),limit,offset).query(this::mapDraft).list());
    }
    @Override public CatalogResource update(WorkspaceActor actor,UUID id,long revision,String name,JsonNode content) {
        return tx.execute(status -> {
        int changed=jdbc.sql("update catalog_resources set name=?,content=cast(? as jsonb),revision=revision+1 where id=? and workspace_id=? and revision=? and archived=false")
            .params(name,content.toString(),id,actor.workspaceId(),revision).update();
        if(changed==0) { draft(actor,id); throw new CatalogConflictException(); }
        io.agenteval.administration.JdbcAuditWriter.append(jdbc,actor,io.agenteval.administration.AuditAction.DRAFT_UPDATED,id);
        return draft(actor,id);
        });
    }
    @Override public PublishedVersion publish(WorkspaceActor actor,CatalogResource expected,String hash) {
        return tx.execute(status -> {
            var current=jdbc.sql("select * from catalog_resources where id=? and workspace_id=? for update").params(expected.id(),actor.workspaceId()).query(this::mapDraft).optional().orElseThrow(ScopeViolationException::new);
            if(current.archived() || current.revision()!=expected.revision()) throw new CatalogConflictException();
            var existing=jdbc.sql("select * from catalog_versions where resource_id=? and workspace_id=? and draft_revision=?").params(current.id(),actor.workspaceId(),current.revision()).query(this::mapVersion).optional();
            if(existing.isPresent()) return existing.get();
            io.agenteval.administration.JdbcResourceAdmission.metadata(jdbc,actor.workspaceId(),"catalog_versions");
            if(current.kind()==ResourceKind.SUITE) for(var entry:current.content().path("entries")) {
                jdbc.sql("select id from catalog_versions where id=? and workspace_id=? and kind='EVALUATOR' for key share").params(UUID.fromString(entry.path("evaluator_version").asText()),actor.workspaceId()).query(UUID.class).optional().orElseThrow(ScopeViolationException::new);
            }
            int next=jdbc.sql("select coalesce(max(number),0)+1 from catalog_versions where resource_id=? and workspace_id=?").params(current.id(),actor.workspaceId()).query(Integer.class).single();
            UUID id=UUID.randomUUID();
            jdbc.sql("insert into catalog_versions(id,resource_id,workspace_id,kind,name,content,content_hash,number,draft_revision) values (?,?,?,?,?,cast(? as jsonb),?,?,?)")
                .params(id,current.id(),actor.workspaceId(),current.kind().name(),current.name(),current.content().toString(),hash,next,current.revision()).update();
            io.agenteval.administration.JdbcAuditWriter.append(jdbc,actor,io.agenteval.administration.AuditAction.VERSION_PUBLISHED,id);
            return version(actor,id);
        });
    }
    @Override public PublishedVersion version(WorkspaceActor actor,UUID versionId) {
        return jdbc.sql("select * from catalog_versions where id=? and workspace_id=?").params(versionId,actor.workspaceId()).query(this::mapVersion).optional().orElseThrow(ScopeViolationException::new);
    }
    @Override public void archive(WorkspaceActor actor,UUID id) {
        tx.executeWithoutResult(status -> {
            if(jdbc.sql("update catalog_resources set revision=revision+1,archived=true where id=? and workspace_id=? and not archived").params(id,actor.workspaceId()).update()==0) { draft(actor,id); return; }
            io.agenteval.administration.JdbcAuditWriter.append(jdbc,actor,io.agenteval.administration.AuditAction.RESOURCE_ARCHIVED,id);
        });
    }
    private CatalogResource mapDraft(java.sql.ResultSet row,int index) throws java.sql.SQLException {
        return new CatalogResource(row.getObject("id",UUID.class),row.getObject("workspace_id",UUID.class),ResourceKind.valueOf(row.getString("kind")),row.getString("name"),tree(row.getString("content")),row.getBoolean("archived"),row.getLong("revision"));
    }
    private PublishedVersion mapVersion(java.sql.ResultSet row,int index) throws java.sql.SQLException {
        return new PublishedVersion(row.getObject("id",UUID.class),row.getObject("resource_id",UUID.class),ResourceKind.valueOf(row.getString("kind")),row.getString("name"),tree(row.getString("content")),row.getString("content_hash"),row.getInt("number"));
    }
    private JsonNode tree(String source) { try { return json.readTree(source); } catch(java.io.IOException invalid) { throw new IllegalStateException("Corrupt persisted catalog",invalid); } }
}
