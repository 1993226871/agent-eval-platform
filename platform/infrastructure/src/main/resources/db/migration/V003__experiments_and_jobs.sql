CREATE TABLE agents (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    name VARCHAR(200) NOT NULL,
    version VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(id,workspace_id)
);
CREATE TABLE experiments (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    agent_id UUID NOT NULL,
    agent_version VARCHAR(128) NOT NULL,
    dataset_version_id UUID NOT NULL,
    dataset_kind VARCHAR(16) NOT NULL DEFAULT 'DATASET' CHECK(dataset_kind='DATASET'),
    suite_version_id UUID NOT NULL,
    suite_kind VARCHAR(16) NOT NULL DEFAULT 'SUITE' CHECK(suite_kind='SUITE'),
    repeat_count INT NOT NULL CHECK(repeat_count BETWEEN 1 AND 20),
    deadline TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    snapshot JSONB NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(id,workspace_id),
    UNIQUE(workspace_id,idempotency_key),
    FOREIGN KEY(agent_id,workspace_id) REFERENCES agents(id,workspace_id),
    FOREIGN KEY(dataset_version_id,workspace_id,dataset_kind) REFERENCES catalog_versions(id,workspace_id,kind),
    FOREIGN KEY(suite_version_id,workspace_id,suite_kind) REFERENCES catalog_versions(id,workspace_id,kind)
);
CREATE TABLE trials (
    id UUID PRIMARY KEY,
    experiment_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    case_index INT NOT NULL,
    attempt INT NOT NULL,
    trace_id CHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','LEASED','RUNNING','SUCCEEDED','FAILED','TIMED_OUT','CANCELLED','EXECUTION_UNKNOWN')),
    task JSONB NOT NULL,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(id,workspace_id),
    UNIQUE(experiment_id,case_index,attempt),
    FOREIGN KEY(experiment_id,workspace_id) REFERENCES experiments(id,workspace_id)
);
CREATE INDEX trials_queue_idx ON trials(status,next_run_at,created_at,id);
CREATE INDEX trials_scope_experiment_idx ON trials(workspace_id,experiment_id,case_index,attempt);
