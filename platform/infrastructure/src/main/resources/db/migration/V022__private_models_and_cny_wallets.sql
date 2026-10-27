-- Legacy balances and price versions retain their integer-credit meaning.
ALTER TABLE quota_wallets ADD COLUMN unit VARCHAR(16) NOT NULL DEFAULT 'CREDITS'
    CHECK(unit IN ('CREDITS','CNY_MICRO'));
ALTER TABLE model_profiles ADD COLUMN workspace_id UUID REFERENCES workspaces(id);
ALTER TABLE model_profiles ADD COLUMN billing_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY'
    CHECK(billing_mode IN ('LEGACY','PLATFORM','BYOK'));
ALTER TABLE model_profiles ADD CONSTRAINT private_model_billing CHECK((billing_mode='BYOK')=(workspace_id IS NOT NULL));
CREATE INDEX model_profiles_workspace ON model_profiles(workspace_id,created_at DESC);

-- BYOK calls keep dispatch/recovery state but have no platform reservation.
ALTER TABLE model_calls DROP CONSTRAINT model_calls_upper_credits_check;
ALTER TABLE model_calls ADD CONSTRAINT model_calls_upper_credits_check CHECK(upper_credits>=0);
