ALTER TABLE experiments ALTER COLUMN agent_id DROP NOT NULL;
ALTER TABLE experiments ALTER COLUMN dataset_version_id DROP NOT NULL;
ALTER TABLE experiments ALTER COLUMN suite_version_id DROP NOT NULL;
ALTER TABLE experiments ADD CONSTRAINT live_experiment_requires_versions CHECK(status='DELETED' OR (agent_id IS NOT NULL AND dataset_version_id IS NOT NULL AND suite_version_id IS NOT NULL));
CREATE TABLE ephemeral_catalog_resources (
    resource_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    resource_kind VARCHAR(16) NOT NULL CHECK(resource_kind IN ('EVALUATOR','SUITE')),
    origin_experiment_id UUID NOT NULL,
    FOREIGN KEY(resource_id,workspace_id,resource_kind) REFERENCES catalog_resources(id,workspace_id,kind),
    FOREIGN KEY(origin_experiment_id,workspace_id) REFERENCES experiments(id,workspace_id)
);
INSERT INTO ephemeral_catalog_resources(resource_id,workspace_id,resource_kind,origin_experiment_id)
    SELECT DISTINCT r.id,r.workspace_id,r.kind,t.experiment_id FROM grading_jobs g JOIN trials t ON t.id=g.trial_id
    JOIN catalog_versions v ON v.workspace_id=g.workspace_id AND (v.id=g.suite_version_id OR EXISTS(SELECT 1 FROM jsonb_array_elements(g.run_config->'evaluators') item WHERE item->>'id'=v.id::text))
    JOIN catalog_resources r ON r.id=v.resource_id AND r.archived WHERE g.purpose='PREVIEW'
    ON CONFLICT(resource_id) DO NOTHING;
