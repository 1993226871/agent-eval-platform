-- One monetary convention: every stored integer amount is a micro-yuan.
-- Existing balances and immutable ledger amounts are unchanged.
ALTER TABLE quota_wallets DROP COLUMN unit;
UPDATE model_profiles SET billing_mode='PLATFORM' WHERE billing_mode='LEGACY';
ALTER TABLE model_profiles ALTER COLUMN billing_mode SET DEFAULT 'PLATFORM';
ALTER TABLE model_profiles DROP CONSTRAINT model_profiles_billing_mode_check;
ALTER TABLE model_profiles ADD CONSTRAINT model_profiles_billing_mode_check
    CHECK(billing_mode IN ('PLATFORM','BYOK'));
