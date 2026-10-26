ALTER TABLE trials ADD COLUMN evidence_next_attempt_at timestamptz NOT NULL DEFAULT '-infinity';
ALTER TABLE trials ADD COLUMN evidence_last_attempt_at timestamptz NOT NULL DEFAULT '-infinity';
CREATE INDEX trials_evidence_retry_queue ON trials(evidence_last_attempt_at,completed_at,id)
    WHERE completed_at IS NOT NULL;
