-- V22: lot-based points expiry + breakage.
--
-- Each credit opens a "lot" of points with its own expiry (earned_at + TTL).
-- Redemptions burn lots FIFO (soonest-to-expire first); a daily sweep (and a
-- lazy release on every wallet touch) zeroes expired remainders and records the
-- released points as "breakage" in points_ledger. The wallet's cached balance
-- equals the sum of remaining across LIVE lots.
--
-- Backend-only; no API/DTO/enum changes. Breakage is a ledger entry (internal
-- audit), not a LoyaltyTransaction, so customer-facing history/enums are
-- untouched.

-- Breakage ledger entries belong to no single tenant (the wallet is global),
-- so tenant_id must allow null. Normal earn/redeem/transfer entries still set it.
ALTER TABLE points_ledger ALTER COLUMN tenant_id DROP NOT NULL;

CREATE TABLE point_lot (
    id                    UUID PRIMARY KEY,
    wallet_id             UUID NOT NULL REFERENCES wallets(id) ON DELETE CASCADE,
    tenant_id             UUID,
    source_transaction_id UUID,
    original_amount       NUMERIC(19,4) NOT NULL,
    remaining_amount      NUMERIC(19,4) NOT NULL,
    earned_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_point_lot_remaining CHECK (remaining_amount >= 0 AND remaining_amount <= original_amount)
);

-- FIFO consumption + per-wallet expiry lookups.
CREATE INDEX idx_point_lot_wallet_active ON point_lot (wallet_id, expires_at);
-- The sweep only cares about lots that still hold points.
CREATE INDEX idx_point_lot_expiry ON point_lot (expires_at) WHERE remaining_amount > 0;

-- Backfill: every existing positive balance becomes one lot, expiring 30 days
-- out so nothing expires the instant this ships (matches the default
-- loyalty.points.expiry-days=30 — change the interval if you deploy a different
-- TTL). On a fresh DB (incl. the test container) wallets is empty -> no-op.
INSERT INTO point_lot (id, wallet_id, original_amount, remaining_amount, earned_at, expires_at)
SELECT gen_random_uuid(), w.id, w.balance, w.balance, NOW(), NOW() + INTERVAL '30 days'
FROM wallets w
WHERE w.balance > 0;
