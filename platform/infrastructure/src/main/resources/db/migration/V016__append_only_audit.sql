CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    actor_user_id UUID REFERENCES users(id),
    action VARCHAR(64) NOT NULL,
    entity_id UUID NOT NULL,
    source VARCHAR(16) NOT NULL CHECK(source IN ('APPLICATION','SYSTEM')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX audit_workspace_cursor ON audit_events(workspace_id,created_at DESC,id DESC);
CREATE TRIGGER immutable_audit_event BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION reject_quota_ledger_mutation();
