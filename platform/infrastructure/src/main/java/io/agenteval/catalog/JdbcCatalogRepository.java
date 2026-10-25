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
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public JdbcCatalogRepository(JdbcClient jdbc,PlatformTransactionManager manager,ObjectMapper json) { this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.json=json; }
    @Override public CatalogResource create(WorkspaceActor actor,ResourceKind kind,String name,JsonNode content) {
        UUID id=UUID.randomUUID();
        jdbc.sql("insert into catalog_resources(id,workspace_id,kind,name,content) values (?,?,?,?,cast(? as jsonb))").params(id,actor.workspaceId(),kind.name(),name,content.toString()).update();
        return draft(actor,id);
    }
    @Override public CatalogResource draft(WorkspaceActor actor,UUID id) {
        return jdbc.sql("select * from catalog_resources where id=? and workspace_id=?").params(id,actor.workspaceId()).query(this::mapDraft).optional().orElseThrow(ScopeViolationException::new);
    }
    @Override public List<CatalogResource> list(WorkspaceActor actor,ResourceKind kind,int offset,int limit) {
        return jdbc.sql("select * from catalog_resources where workspace_id=? and kind=? and archived=false order by created_at,id limit ? offset ?")
            .params(actor.workspaceId(),kind.name(),limit,offset).query(this::mapDraft).list();
    }
    @Override public CatalogResource update(WorkspaceActor actor,UUID id,long revision,String name,JsonNode content) {
        int changed=jdbc.sql("update catalog_resources set name=?,content=cast(? as jsonb),revision=revision+1 where id=? and workspace_id=? and revision=? and archived=false")
            .params(name,content.toString(),id,actor.workspaceId(),revision).update();
        if(changed==0) { draft(actor,id); throw new CatalogConflictException(); }
        return draft(actor,id);
    }
    @Override public PublishedVersion publish(WorkspaceActor actor,CatalogResource expected,String hash) {
        return tx.execute(status -> {
            var current=jdbc.sql("select * from catalog_resources where id=? and workspace_id=? for update").params(expected.id(),actor.workspaceId()).query(this::mapDraft).optional().orElseThrow(ScopeViolationException::new);
            if(current.archived() || current.revision()!=expected.revision()) throw new CatalogConflictException();
            var existing=jdbc.sql("select * from catalog_versions where resource_id=? and workspace_id=? and draft_revision=?").params(current.id(),actor.workspaceId(),current.revision()).query(this::mapVersion).optional();
            if(existing.isPresent()) return existing.get();
            int next=jdbc.sql("select coalesce(max(number),0)+1 from catalog_versions where resource_id=? and workspace_id=?").params(current.id(),actor.workspaceId()).query(Integer.class).single();
            UUID id=UUID.randomUUID();
            jdbc.sql("insert into catalog_versions(id,resource_id,workspace_id,kind,name,content,content_hash,number,draft_revision) values (?,?,?,?,?,cast(? as jsonb),?,?,?)")
                .params(id,current.id(),actor.workspaceId(),current.kind().name(),current.name(),current.content().toString(),hash,next,current.revision()).update();
            return version(actor,id);
        });
    }
    @Override public PublishedVersion version(WorkspaceActor actor,UUID versionId) {
        return jdbc.sql("select * from catalog_versions where id=? and workspace_id=?").params(versionId,actor.workspaceId()).query(this::mapVersion).optional().orElseThrow(ScopeViolationException::new);
    }
    @Override public void archive(WorkspaceActor actor,UUID id) {
        if(jdbc.sql("update catalog_resources set revision=case when archived then revision else revision+1 end,archived=true where id=? and workspace_id=?").params(id,actor.workspaceId()).update()==0) throw new ScopeViolationException();
    }
    private CatalogResource mapDraft(java.sql.ResultSet row,int index) throws java.sql.SQLException {
        return new CatalogResource(row.getObject("id",UUID.class),row.getObject("workspace_id",UUID.class),ResourceKind.valueOf(row.getString("kind")),row.getString("name"),tree(row.getString("content")),row.getBoolean("archived"),row.getLong("revision"));
    }
    private PublishedVersion mapVersion(java.sql.ResultSet row,int index) throws java.sql.SQLException {
        return new PublishedVersion(row.getObject("id",UUID.class),row.getObject("resource_id",UUID.class),ResourceKind.valueOf(row.getString("kind")),row.getString("name"),tree(row.getString("content")),row.getString("content_hash"),row.getInt("number"));
    }
    private JsonNode tree(String source) { try { return json.readTree(source); } catch(java.io.IOException invalid) { throw new IllegalStateException("Corrupt persisted catalog",invalid); } }
}
