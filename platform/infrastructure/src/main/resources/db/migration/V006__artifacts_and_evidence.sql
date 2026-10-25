CREATE TABLE artifacts (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    trial_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    media_type VARCHAR(200) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK(size_bytes BETWEEN 0 AND 104857600),
    sha256 CHAR(64) NOT NULL,
    staging_key VARCHAR(250) NOT NULL UNIQUE,
    object_key VARCHAR(250) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','VERIFYING','AVAILABLE')),
    idempotency_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    verification_token UUID,
    verification_started_at TIMESTAMPTZ,
    UNIQUE(trial_id,idempotency_key),
    UNIQUE(id,workspace_id),
    FOREIGN KEY(trial_id,workspace_id) REFERENCES trials(id,workspace_id)
);
CREATE INDEX artifacts_trial_idx ON artifacts(workspace_id,trial_id);
CREATE TABLE evidence_snapshots (
    ordinal BIGSERIAL UNIQUE,
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    trial_id UUID NOT NULL,
    content_hash CHAR(64) NOT NULL,
    completeness VARCHAR(16) NOT NULL CHECK(completeness IN ('NONE','PARTIAL','COMPLETE')),
    manifest JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(trial_id,content_hash),
    UNIQUE(id,workspace_id),
    FOREIGN KEY(trial_id,workspace_id) REFERENCES trials(id,workspace_id)
);
CREATE TABLE grading_jobs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    trial_id UUID NOT NULL,
    snapshot_id UUID NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    next_run_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(trial_id,workspace_id) REFERENCES trials(id,workspace_id),
    FOREIGN KEY(snapshot_id,workspace_id) REFERENCES evidence_snapshots(id,workspace_id)
);
CREATE INDEX grading_jobs_ready_idx ON grading_jobs(status,next_run_at);
