ALTER TABLE grading_jobs ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'INITIAL' CHECK(purpose IN ('INITIAL','REEVALUATION','PREVIEW'));
UPDATE grading_jobs SET purpose='REEVALUATION' WHERE NOT initial_run;
ALTER TABLE grading_jobs ADD CONSTRAINT preview_run_scope UNIQUE(id,workspace_id,trial_id);
CREATE TABLE evaluator_previews (
    run_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    trial_id UUID NOT NULL,
    draft_id UUID NOT NULL,
    draft_kind VARCHAR(16) NOT NULL DEFAULT 'EVALUATOR' CHECK(draft_kind='EVALUATOR'),
    draft_revision BIGINT NOT NULL CHECK(draft_revision>=1),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(run_id,workspace_id,trial_id) REFERENCES grading_jobs(id,workspace_id,trial_id),
    FOREIGN KEY(draft_id,workspace_id,draft_kind) REFERENCES catalog_resources(id,workspace_id,kind),
    UNIQUE(workspace_id,trial_id,idempotency_key)
);
