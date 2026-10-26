CREATE TABLE experiment_deletions (
    experiment_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    generation UUID NOT NULL UNIQUE,
    request_key UUID NOT NULL,
    requested_by UUID NOT NULL REFERENCES users(id),
    requested_at TIMESTAMPTZ NOT NULL,
    not_before TIMESTAMPTZ NOT NULL,
    next_cleanup_at TIMESTAMPTZ NOT NULL,
    cleanup_token UUID,
    lease_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failure_count INT NOT NULL DEFAULT 0,
    UNIQUE(workspace_id,request_key),
    UNIQUE(experiment_id,workspace_id),
    FOREIGN KEY(experiment_id,workspace_id) REFERENCES experiments(id,workspace_id)
);
CREATE INDEX experiment_deletion_ready ON experiment_deletions(next_cleanup_at);
-- Retain server-generated object tombstones after private history deletion: a signed PUT already in flight can finish late.
CREATE TABLE deleted_artifact_objects (
    experiment_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    artifact_id UUID NOT NULL,
    staging_key VARCHAR(250) NOT NULL,
    sealed_key VARCHAR(250) NOT NULL,
    cleaned_at TIMESTAMPTZ,
    PRIMARY KEY(experiment_id,artifact_id),
    FOREIGN KEY(experiment_id,workspace_id) REFERENCES experiment_deletions(experiment_id,workspace_id)
);
ALTER TABLE model_calls ADD COLUMN evidence_deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE model_calls ADD COLUMN deleted_experiment_id UUID;
ALTER TABLE model_calls ADD COLUMN deleted_job_id UUID;
CREATE FUNCTION scrub_deleted_model_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.evidence_deleted THEN
        NEW.request_json := NEW.request_json || '{"system_prompt":"[deleted]","user_prompt":""}'::jsonb;
        NEW.response_json := NEW.response_json || '{"content":null,"raw_response":null}'::jsonb;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER scrub_deleted_model_call BEFORE INSERT OR UPDATE ON model_calls FOR EACH ROW EXECUTE FUNCTION scrub_deleted_model_evidence();
