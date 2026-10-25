ALTER TABLE trials ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE grading_jobs ADD COLUMN scheduled_at TIMESTAMPTZ;
ALTER TABLE grading_jobs ADD COLUMN completed_at TIMESTAMPTZ;
ALTER TABLE grading_jobs ADD CONSTRAINT grading_run_scope UNIQUE(id,workspace_id,trial_id,snapshot_id);
CREATE TABLE evaluation_jobs (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    trial_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    evaluator_version UUID NOT NULL,
    evaluator_kind VARCHAR(16) NOT NULL DEFAULT 'EVALUATOR' CHECK(evaluator_kind='EVALUATOR'),
    definition JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','RUNNING','DONE','CANCELLED')),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE(run_id,evaluator_version),
    UNIQUE(id,workspace_id,run_id,snapshot_id,trial_id,evaluator_version),
    FOREIGN KEY(run_id,workspace_id,trial_id,snapshot_id) REFERENCES grading_jobs(id,workspace_id,trial_id,snapshot_id),
    FOREIGN KEY(evaluator_version,workspace_id,evaluator_kind) REFERENCES catalog_versions(id,workspace_id,kind)
);
CREATE INDEX evaluation_jobs_ready_idx ON evaluation_jobs(status,created_at,id);
CREATE TABLE evaluation_results (
    job_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    run_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    trial_id UUID NOT NULL,
    evaluator_version UUID NOT NULL,
    payload JSONB NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(job_id,workspace_id,run_id,snapshot_id,trial_id,evaluator_version) REFERENCES evaluation_jobs(id,workspace_id,run_id,snapshot_id,trial_id,evaluator_version)
);
CREATE INDEX evaluation_results_run_idx ON evaluation_results(workspace_id,run_id);
