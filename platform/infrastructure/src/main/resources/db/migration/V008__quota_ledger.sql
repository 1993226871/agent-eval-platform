CREATE TABLE quota_wallets (
    workspace_id UUID PRIMARY KEY REFERENCES workspaces(id),
    granted BIGINT NOT NULL DEFAULT 0 CHECK(granted>=0),
    consumed BIGINT NOT NULL DEFAULT 0 CHECK(consumed>=0 AND consumed<=granted),
    reserved BIGINT NOT NULL DEFAULT 0 CHECK(reserved>=0 AND reserved<=granted-consumed)
);
INSERT INTO quota_wallets(workspace_id) SELECT id FROM workspaces;
CREATE FUNCTION initialize_quota_wallet() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN INSERT INTO quota_wallets(workspace_id) VALUES(NEW.id); RETURN NEW; END $$;
CREATE TRIGGER workspace_quota_wallet AFTER INSERT ON workspaces FOR EACH ROW EXECUTE FUNCTION initialize_quota_wallet();

CREATE TABLE quota_reservations (
    call_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES quota_wallets(workspace_id),
    upper_credits BIGINT NOT NULL CHECK(upper_credits>0),
    state VARCHAR(24) NOT NULL CHECK(state IN ('RESERVED','SEND_INTENT','RECONCILIATION','SETTLED','RELEASED')),
    actual_credits BIGINT CHECK(actual_credits>=0 AND actual_credits<=upper_credits),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(workspace_id,call_id),
    CHECK((state='SETTLED')=(actual_credits IS NOT NULL))
);
CREATE TABLE quota_ledger (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES quota_wallets(workspace_id),
    call_id UUID,
    entry_type VARCHAR(24) NOT NULL CHECK(entry_type IN ('ADJUST','RESERVE','SETTLE','RELEASE','SEND_INTENT','RECONCILIATION')),
    granted_delta BIGINT NOT NULL DEFAULT 0,
    consumed_delta BIGINT NOT NULL DEFAULT 0,
    reserved_delta BIGINT NOT NULL DEFAULT 0,
    reason VARCHAR(500) NOT NULL,
    actor_user_id UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(workspace_id,call_id) REFERENCES quota_reservations(workspace_id,call_id),
    UNIQUE(call_id,entry_type),
    CHECK((entry_type='ADJUST')=(call_id IS NULL)),
    CHECK((entry_type='ADJUST')=(actor_user_id IS NOT NULL))
);
CREATE INDEX quota_ledger_workspace_time ON quota_ledger(workspace_id,created_at DESC,id DESC);
CREATE FUNCTION reject_quota_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Quota ledger is immutable'; END $$;
CREATE TRIGGER immutable_quota_ledger BEFORE UPDATE OR DELETE ON quota_ledger FOR EACH ROW EXECUTE FUNCTION reject_quota_ledger_mutation();
