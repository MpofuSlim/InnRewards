-- Voucher peer-to-peer transfer, SINGLE HOP.
--
-- A voucher may change hands exactly once: issued -> transferred -> redeemed.
-- The recipient of a transfer cannot pass it on again.
--
-- The single-hop rule is expressed as "transferred_at IS NULL" rather than a
-- transfer counter on purpose. The rule is a boolean fact about the voucher's
-- history, and a timestamp records that fact plus when it happened, which is
-- what a support query actually asks. A counter would invite the question
-- "why not raise the limit to 2?" — the limit is not a tunable, it is the
-- product decision that a voucher is not a circulating instrument.
--
-- All three columns are nullable: every voucher issued before this migration,
-- and every voucher that is never transferred, has no transfer to record. NULL
-- here means "still with its original assignee", which is the common case and
-- not missing data.

ALTER TABLE vouchers
    ADD COLUMN IF NOT EXISTS transferred_at TIMESTAMPTZ;

-- The assignee the voucher moved AWAY from, captured at transfer time. The
-- current holder is still assigned_user_id / assignee_phone, so these two
-- columns are what let a report or a support agent answer "who did this come
-- from?" after the reassignment has overwritten the original values.
ALTER TABLE vouchers
    ADD COLUMN IF NOT EXISTS transferred_from_user_id UUID;

ALTER TABLE vouchers
    ADD COLUMN IF NOT EXISTS transferred_from_phone VARCHAR(32);

-- Partial index: only transferred vouchers are indexed, which is a small
-- minority of the table. Supports "show me every voucher that changed hands"
-- (fraud review, transfer-volume reporting) without carrying an entry for the
-- overwhelming majority of never-transferred rows.
CREATE INDEX IF NOT EXISTS idx_voucher_transferred_at
    ON vouchers(transferred_at)
    WHERE transferred_at IS NOT NULL;
