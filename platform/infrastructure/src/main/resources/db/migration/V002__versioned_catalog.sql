CREATE TABLE catalog_resources (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    kind VARCHAR(16) NOT NULL CHECK(kind IN ('DATASET','EVALUATOR','SUITE')),
    name VARCHAR(200) NOT NULL,
    content JSONB NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(id,workspace_id,kind)
);
CREATE INDEX catalog_scope_kind_idx ON catalog_resources(workspace_id,kind,created_at,id);
CREATE TABLE catalog_versions (
    id UUID PRIMARY KEY,
    resource_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL,
    name VARCHAR(200) NOT NULL,
    content JSONB NOT NULL,
    content_hash CHAR(64) NOT NULL,
    number INT NOT NULL,
    draft_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(resource_id,workspace_id,kind) REFERENCES catalog_resources(id,workspace_id,kind),
    UNIQUE(resource_id,draft_revision),
    UNIQUE(resource_id,number),
    UNIQUE(id,workspace_id,kind)
);
