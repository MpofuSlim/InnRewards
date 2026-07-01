-- Per-operation Oradian-sync tracking. PointsLedger captures the LOCAL
-- atomic balance delta; oradian_sync_transactions captures the corresponding
-- Oradian credit/withdraw attempt with Oradian-assigned IDs.
--
-- Lifecycle:
--
--   1. WalletService opens a row in PENDING just before calling the
--      Oradian middleware.
--   2. On a successful upstream call: row flips to SUCCEEDED in the same
--      DB transaction as the wallet.balance update + the PointsLedger
--      insert. Oradian-assigned identifiers are captured on the row.
--   3. On upstream failure: row flips to FAILED, wallet.balance is NOT
--      mutated, no PointsLedger row.
--
-- Reconciliation: a scheduled job scans rows stuck in PENDING beyond the
-- grace window, hits GET /internal/deposits/{accountId} on the middleware
-- (idempotency-key replay reveals the original outcome), and finalises
-- the row + balance + ledger.
--
-- Balance audit: a separate nightly job sums SUCCEEDED rows per wallet,
-- compares to wallet.balance + Oradian's account balance, alerts on drift.

CREATE TABLE oradian_sync_transactions (
    id                          UUID PRIMARY KEY,
    tenant_id                   UUID NOT NULL REFERENCES tenants(id),
    wallet_id                   UUID NOT NULL REFERENCES wallets(id),

    -- Signed delta in points. Positive = earn / Oradian credit;
    -- negative = spend / Oradian withdraw. Mirrors PointsLedger.delta
    -- semantics so the two tables join cleanly on (wallet_id, delta,
    -- created_at) for forensic reconciliation.
    delta_points                NUMERIC(19, 4) NOT NULL,

    -- Free-form reason aligned with PointsLedger.reason
    -- ("earn:PURCHASE", "redeem:VOUCHER", "transfer-in", ...). Same
    -- string in both tables so an ops join shows one row each side.
    reason                      VARCHAR(200) NOT NULL,

    -- PENDING on insert; SUCCEEDED or FAILED at terminal state. The
    -- partial index below supports the reconciliation job's
    -- "stale PENDING" scan without touching the much larger SUCCEEDED
    -- backlog.
    status                      VARCHAR(16) NOT NULL
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),

    -- The LPW account on Oradian we're crediting / withdrawing against.
    -- Captured at insertion time, not lazily — we won't attempt an
    -- upstream call without knowing the target account.
    oradian_account_id          VARCHAR(64) NOT NULL,

    -- Oradian-assigned identifiers captured on SUCCEEDED. NULL while
    -- PENDING or on FAILED.
    oradian_transaction_id      VARCHAR(64),
    oradian_command_id          VARCHAR(64),
    oradian_reference_number    VARCHAR(64),

    -- Classification on FAILED:
    --   UPSTREAM_REJECTED      — Oradian 4xx (validation, insufficient
    --                            funds on withdraw, account suspended)
    --   UPSTREAM_UNAVAILABLE   — Oradian 5xx / timeout / circuit-open
    --   VALIDATION_FAILED      — local sanity-check rejected the call
    --                            before we hit the wire
    failure_code                VARCHAR(64),
    failure_message             VARCHAR(500),

    -- Optional pointer to the upstream business transaction that
    -- triggered the sync (a loyalty.transactions row for an earn, a
    -- redemption for a spend, etc.). Mirrors PointsLedger.transaction_id
    -- so the same upstream event groups across the two ledgers.
    source_transaction_id       UUID,

    correlation_id              VARCHAR(64),
    created_at                  TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at                TIMESTAMP
);

CREATE INDEX idx_oradian_sync_transactions_wallet
    ON oradian_sync_transactions(wallet_id, created_at DESC);

-- Partial index: only the rows the reconciliation job cares about.
-- PENDING is normally a transient state (seconds), so the index stays
-- small; SUCCEEDED accumulates millions of rows that we don't want
-- here.
CREATE INDEX idx_oradian_sync_transactions_pending
    ON oradian_sync_transactions(created_at)
    WHERE status = 'PENDING';

-- "Everything we did against this Oradian account" — used by the
-- balance-audit job and by ad-hoc forensics when a customer disputes
-- their LPW balance.
CREATE INDEX idx_oradian_sync_transactions_oradian_account
    ON oradian_sync_transactions(oradian_account_id, created_at DESC);


-- Wallet → LPW Oradian account mapping. Nullable because:
--
--   (a) Existing wallets predate the Oradian-sync rollout; their
--       account ID is discovered lazily on first sync attempt via
--       GET /auth/customer/deposits + filter productID == "LPW".
--   (b) Wallets attached to PENDING loyalty users (pre-tier-2, no
--       Oradian customer yet) won't have an LPW account until the
--       customer is promoted. Those wallets stay local-only and get
--       backfilled to Oradian at promotion time.
--
-- One LPW account belongs to at most one wallet (enforced by the
-- partial unique index below), so a future "find the wallet that
-- owns this Oradian account" lookup is an index seek.
ALTER TABLE wallets
    ADD COLUMN oradian_account_id VARCHAR(64);

CREATE UNIQUE INDEX idx_wallets_oradian_account_id
    ON wallets(oradian_account_id)
    WHERE oradian_account_id IS NOT NULL;
