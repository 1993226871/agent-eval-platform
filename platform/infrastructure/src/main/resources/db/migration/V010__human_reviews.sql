ALTER TABLE evaluation_results ADD CONSTRAINT evaluation_result_workspace UNIQUE(job_id,workspace_id);
CREATE TABLE human_reviews (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    result_id UUID NOT NULL,
    score NUMERIC(8,6) NOT NULL CHECK(score>=0 AND score<=1),
    reason VARCHAR(2048) NOT NULL,
    reviewer_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(result_id,workspace_id) REFERENCES evaluation_results(job_id,workspace_id),
    FOREIGN KEY(workspace_id,reviewer_id) REFERENCES workspaces(id,owner_user_id)
);
CREATE INDEX human_reviews_result ON human_reviews(workspace_id,result_id,created_at DESC,id DESC);
-- Explicit deletion of an experiment can remove its history; edits cannot rewrite old judgments.
CREATE TRIGGER immutable_human_review BEFORE UPDATE ON human_reviews FOR EACH ROW EXECUTE FUNCTION reject_quota_ledger_mutation();
CREATE TRIGGER immutable_automatic_result BEFORE UPDATE ON evaluation_results FOR EACH ROW EXECUTE FUNCTION reject_quota_ledger_mutation();
