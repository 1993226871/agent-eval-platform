ALTER TABLE workspace_resource_usage ADD COLUMN artifact_bytes BIGINT NOT NULL DEFAULT 0 CHECK(artifact_bytes>=0);
ALTER TABLE workspace_resource_usage ADD COLUMN evidence_bytes BIGINT NOT NULL DEFAULT 0 CHECK(evidence_bytes>=0);
ALTER TABLE trials ADD COLUMN evidence_wait_reason VARCHAR(64);
UPDATE workspace_resource_usage u SET
    artifact_bytes=(SELECT coalesce(sum(size_bytes),0) FROM artifacts a WHERE a.workspace_id=u.workspace_id),
    evidence_bytes=(SELECT coalesce(sum(octet_length(event_json::text))*2,0) FROM trace_events t WHERE t.workspace_id=u.workspace_id)
        +(SELECT coalesce(sum(octet_length(manifest::text)),0) FROM evidence_snapshots s WHERE s.workspace_id=u.workspace_id);
CREATE TABLE trial_evidence_usage (
    trial_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    event_bytes BIGINT NOT NULL DEFAULT 0 CHECK(event_bytes>=0),
    FOREIGN KEY(trial_id,workspace_id) REFERENCES trials(id,workspace_id)
);
INSERT INTO trial_evidence_usage(trial_id,workspace_id,event_bytes)
    SELECT t.id,t.workspace_id,(SELECT coalesce(sum(octet_length(event_json::text)),0) FROM trace_events e WHERE e.trial_id=t.id) FROM trials t;
CREATE FUNCTION initialize_trial_evidence_usage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN INSERT INTO trial_evidence_usage(trial_id,workspace_id) VALUES(NEW.id,NEW.workspace_id); RETURN NEW; END $$;
CREATE TRIGGER trial_evidence_budget AFTER INSERT ON trials FOR EACH ROW EXECUTE FUNCTION initialize_trial_evidence_usage();
