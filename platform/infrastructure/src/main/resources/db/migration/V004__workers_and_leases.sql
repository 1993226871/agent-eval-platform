CREATE TABLE worker_credentials (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    secret_hash CHAR(64) NOT NULL UNIQUE,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(id,workspace_id)
);
CREATE TABLE worker_credential_agents (
    credential_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    PRIMARY KEY(credential_id,agent_id),
    FOREIGN KEY(credential_id,workspace_id) REFERENCES worker_credentials(id,workspace_id),
    FOREIGN KEY(agent_id,workspace_id) REFERENCES agents(id,workspace_id)
);
CREATE TABLE worker_instances (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    agent_version VARCHAR(128) NOT NULL,
    instance_id VARCHAR(128) NOT NULL,
    protocol_version VARCHAR(16) NOT NULL,
    sdk_language VARCHAR(16) NOT NULL,
    sdk_version VARCHAR(128) NOT NULL,
    max_concurrency INT NOT NULL CHECK(max_concurrency BETWEEN 1 AND 8),
    last_seen_at TIMESTAMPTZ NOT NULL,
    UNIQUE(credential_id,instance_id),
    UNIQUE(id,workspace_id),
    FOREIGN KEY(credential_id,workspace_id) REFERENCES worker_credentials(id,workspace_id),
    FOREIGN KEY(agent_id,workspace_id) REFERENCES agents(id,workspace_id)
);
CREATE TABLE scheduler_guard (id INT PRIMARY KEY CHECK(id=1));
INSERT INTO scheduler_guard(id) VALUES (1);
CREATE TABLE trial_leases (
    trial_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    worker_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    execution_deadline TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(trial_id,workspace_id) REFERENCES trials(id,workspace_id),
    FOREIGN KEY(worker_id,workspace_id) REFERENCES worker_instances(id,workspace_id)
);
ALTER TABLE trials ADD COLUMN completion JSONB;
ALTER TABLE trials ADD COLUMN completion_hash CHAR(64);
ALTER TABLE trials ADD COLUMN completed_at TIMESTAMPTZ;
CREATE INDEX trial_lease_expiry_idx ON trial_leases(expires_at);
