-- Retain only opaque dispatch identities after private grading history is removed.
CREATE TABLE runner_executions (
    job_id UUID PRIMARY KEY,
    experiment_id UUID NOT NULL REFERENCES experiments(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    cleaned_at TIMESTAMPTZ,
    UNIQUE(workspace_id,job_id)
);
CREATE INDEX runner_cleanup_experiment ON runner_executions(experiment_id,workspace_id,cleaned_at,job_id);
-- Historical dispatch intent was not tracked: conservatively include every claimed Python job.
-- Even a later unavailable response cannot prove no prior response was cached on the Runner.
INSERT INTO runner_executions(job_id,experiment_id,workspace_id)
    SELECT j.id,t.experiment_id,j.workspace_id FROM evaluation_jobs j JOIN trials t ON t.id=j.trial_id
    WHERE j.definition->>'type'='PYTHON' AND j.python_budget_reserved;
