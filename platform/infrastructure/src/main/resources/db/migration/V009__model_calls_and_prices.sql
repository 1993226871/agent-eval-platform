CREATE TABLE model_profiles (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    endpoint VARCHAR(2048) NOT NULL,
    encrypted_api_key TEXT NOT NULL,
    model VARCHAR(200) NOT NULL,
    input_token_bound BIGINT NOT NULL CHECK(input_token_bound>0),
    max_output_tokens INT NOT NULL CHECK(max_output_tokens>0),
    max_request_bytes INT NOT NULL CHECK(max_request_bytes>0),
    max_concurrent INT NOT NULL CHECK(max_concurrent BETWEEN 1 AND 8),
    bound_reference VARCHAR(1000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    disabled_reason VARCHAR(100),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE model_price_versions (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES model_profiles(id),
    input_rate BIGINT NOT NULL CHECK(input_rate>=0),
    output_rate BIGINT NOT NULL CHECK(output_rate>=0),
    denominator BIGINT NOT NULL CHECK(denominator>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(profile_id,id), CHECK(input_rate>0 OR output_rate>0)
);
CREATE TRIGGER immutable_model_price BEFORE UPDATE OR DELETE ON model_price_versions FOR EACH ROW EXECUTE FUNCTION reject_quota_ledger_mutation();
CREATE TABLE model_gateway_guard(id INT PRIMARY KEY CHECK(id=1));
INSERT INTO model_gateway_guard VALUES(1);
CREATE TABLE model_calls (
    call_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    profile_id UUID NOT NULL REFERENCES model_profiles(id),
    price_version_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    request_json JSONB NOT NULL,
    state VARCHAR(32) NOT NULL CHECK(state IN ('WAITING_QUOTA','WAITING_CAPACITY','RESERVED','SEND_INTENT','PENDING_RECONCILIATION','SUCCEEDED','RECONCILED','FAILED_UNSENT','MODEL_DISABLED')),
    upper_credits BIGINT NOT NULL CHECK(upper_credits>0),
    dispatch_token UUID,
    dispatch_deadline TIMESTAMPTZ,
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(profile_id,price_version_id) REFERENCES model_price_versions(profile_id,id),
    UNIQUE(workspace_id,call_id)
);
CREATE INDEX model_call_active ON model_calls(state,dispatch_deadline);
CREATE INDEX model_call_workspace ON model_calls(workspace_id,created_at DESC);
CREATE TABLE model_reconciliations (
    idempotency_key UUID PRIMARY KEY,
    call_id UUID NOT NULL UNIQUE REFERENCES model_calls(call_id),
    administrator UUID NOT NULL REFERENCES users(id),
    input_tokens BIGINT NOT NULL CHECK(input_tokens>=0),
    output_tokens BIGINT NOT NULL CHECK(output_tokens>=0),
    credits BIGINT NOT NULL CHECK(credits>=0),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TRIGGER immutable_model_reconciliation BEFORE UPDATE OR DELETE ON model_reconciliations FOR EACH ROW EXECUTE FUNCTION reject_quota_ledger_mutation();
