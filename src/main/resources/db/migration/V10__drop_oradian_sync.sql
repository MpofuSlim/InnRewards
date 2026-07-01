-- Rollback of the V9 Oradian-LPW sync scaffolding. We decided to
-- keep loyalty points in the local ledger (wallets + points_ledger
-- + loyalty_transactions) instead of mirroring them to an Oradian
-- deposit account.
--
-- Safe to drop:
--   - The feature flag (loyalty.oradian-sync.enabled) was never
--     flipped on in any deployment, so oradian_sync_transactions
--     never accumulated rows.
--   - wallets.oradian_account_id was populated only by the lazy
--     LPW discovery in OradianSyncService — which was also flag-
--     gated and never ran. The column is empty across all rows.
--
-- DROP COLUMN cascades and removes the partial unique index
-- idx_wallets_oradian_account_id created in V9.

DROP TABLE IF EXISTS oradian_sync_transactions;

ALTER TABLE wallets
    DROP COLUMN IF EXISTS oradian_account_id;
