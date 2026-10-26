CREATE TABLE workspace_resource_limits (
    workspace_id UUID PRIMARY KEY REFERENCES workspaces(id),
    max_online_workers INT NOT NULL DEFAULT 5 CHECK(max_online_workers BETWEEN 1 AND 50),
    max_running_trials INT NOT NULL DEFAULT 2 CHECK(max_running_trials BETWEEN 1 AND 20),
    max_stored_bytes BIGINT NOT NULL DEFAULT 1073741824 CHECK(max_stored_bytes BETWEEN 1 AND 107374182400),
    max_experiments INT NOT NULL DEFAULT 200 CHECK(max_experiments BETWEEN 1 AND 10000),
    max_catalog_resources INT NOT NULL DEFAULT 300 CHECK(max_catalog_resources BETWEEN 1 AND 10000),
    max_published_versions INT NOT NULL DEFAULT 1000 CHECK(max_published_versions BETWEEN 1 AND 100000),
    max_trial_executions BIGINT NOT NULL DEFAULT 10000 CHECK(max_trial_executions BETWEEN 1 AND 1000000),
    max_evaluation_jobs BIGINT NOT NULL DEFAULT 50000 CHECK(max_evaluation_jobs BETWEEN 1 AND 1000000),
    max_python_seconds BIGINT NOT NULL DEFAULT 3600 CHECK(max_python_seconds BETWEEN 30 AND 2592000)
);
CREATE TABLE workspace_resource_usage (
    workspace_id UUID PRIMARY KEY REFERENCES workspaces(id),
    trial_executions BIGINT NOT NULL DEFAULT 0 CHECK(trial_executions>=0),
    evaluation_jobs BIGINT NOT NULL DEFAULT 0 CHECK(evaluation_jobs>=0),
    python_reserved_seconds BIGINT NOT NULL DEFAULT 0 CHECK(python_reserved_seconds>=0)
);
INSERT INTO workspace_resource_limits(workspace_id) SELECT id FROM workspaces;
INSERT INTO workspace_resource_usage(workspace_id,trial_executions,evaluation_jobs,python_reserved_seconds)
    SELECT w.id,(SELECT count(*) FROM trials t WHERE t.workspace_id=w.id),
        (SELECT count(*) FROM evaluation_jobs j WHERE j.workspace_id=w.id),
        (SELECT count(*)*30 FROM evaluation_jobs j WHERE j.workspace_id=w.id AND j.definition->>'type'='PYTHON') FROM workspaces w;
CREATE FUNCTION initialize_resource_limits() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO workspace_resource_limits(workspace_id) VALUES(NEW.id);
    INSERT INTO workspace_resource_usage(workspace_id) VALUES(NEW.id);
    RETURN NEW;
END $$;
CREATE TRIGGER workspace_resource_budget AFTER INSERT ON workspaces FOR EACH ROW EXECUTE FUNCTION initialize_resource_limits();
ALTER TABLE evaluation_jobs ADD COLUMN python_budget_reserved BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE evaluation_jobs SET python_budget_reserved=TRUE WHERE definition->>'type'='PYTHON';
