package io.agenteval.catalog;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Called under scheduler and workspace budget locks. Ownership comes from preview creation, never from user names. */
public final class EphemeralCatalogCleaner {
    private EphemeralCatalogCleaner() {}
    private record Owned(UUID id,String kind) {}
    public static void clean(JdbcClient jdbc,UUID experiment,UUID workspace) {
        var owned=jdbc.sql("select resource_id,resource_kind from ephemeral_catalog_resources where origin_experiment_id=? and workspace_id=? order by case resource_kind when 'SUITE' then 0 else 1 end,resource_id")
            .params(experiment,workspace).query((row,n) -> new Owned(row.getObject("resource_id",UUID.class),row.getString("resource_kind"))).list();
        for(var resource:owned) {
            boolean referenced=jdbc.sql("""
                with versions as (select id from catalog_versions where resource_id=:resource and workspace_id=:workspace)
                select exists(select 1 from experiments e where e.workspace_id=:workspace and (e.suite_version_id in(select id from versions) or e.dataset_version_id in(select id from versions)))
                    or exists(select 1 from grading_jobs g where g.workspace_id=:workspace and g.suite_version_id in(select id from versions))
                    or exists(select 1 from evaluation_jobs j where j.workspace_id=:workspace and j.evaluator_version in(select id from versions))
                """
            ).param("resource",resource.id()).param("workspace",workspace).query(Boolean.class).single();
            if(referenced) continue;
            if(resource.kind().equals("EVALUATOR")) {
                boolean suiteReference=jdbc.sql("""
                    with versions as (select id from catalog_versions where resource_id=:resource and workspace_id=:workspace)
                    select exists(select 1 from catalog_resources r,versions v where r.workspace_id=:workspace and r.kind='SUITE' and r.content @> jsonb_build_object('entries',jsonb_build_array(jsonb_build_object('evaluator_version',v.id::text))))
                        or exists(select 1 from catalog_versions r,versions v where r.workspace_id=:workspace and r.kind='SUITE' and r.content @> jsonb_build_object('entries',jsonb_build_array(jsonb_build_object('evaluator_version',v.id::text))))
                    """
                ).param("resource",resource.id()).param("workspace",workspace).query(Boolean.class).single();
                if(suiteReference) continue;
            }
            jdbc.sql("delete from catalog_versions where resource_id=? and workspace_id=?").params(resource.id(),workspace).update();
            jdbc.sql("delete from ephemeral_catalog_resources where resource_id=? and workspace_id=? and origin_experiment_id=?").params(resource.id(),workspace,experiment).update();
            jdbc.sql("delete from catalog_resources where id=? and workspace_id=? and archived").params(resource.id(),workspace).update();
        }
    }
}
