-- V61 — vouchers hold a BALANCE; a redemption spends part or all of it.
--
-- Owner decision (2026-10-21): every voucher is a balance voucher. A $50
-- voucher can be used in several partial redemptions; it is PARTIALLY_USED
-- until its balance reaches zero, then REDEEMED. A redemption with no amount
-- spends the whole remaining balance, so a $5 voucher used in one go behaves
-- exactly as it always did. MULTI_USE stays retired: what is counted now is
-- money, not uses.
--
-- Until now a voucher had a face value and a bare use counter, with no
-- remainder anywhere — the gap V49 closed by refusing multi-use rather than by
-- giving the voucher a balance. This migration adds the balance, and makes
-- each redemption row carry the money it moved, so billing and reports can be
-- driven by uses instead of by `vouchers.redeemed_at` (which is only stamped
-- when the balance hits zero, so a part-used voucher was never billed and
-- never counted).
--
-- Additive throughout: new nullable columns (plus one NOT NULL with a
-- default), two indexes, one CHECK. The previous build ignores all of it on a
-- rollback; it would simply go back to spending a voucher whole.

-- ---------------------------------------------------------------------------
-- 1. vouchers.remaining_balance — what is left to spend, in the voucher's own
--    currency (the same unit as face_value). NULL means "no balance recorded":
--    see the backfill below for which rows keep it.
ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS remaining_balance NUMERIC(19,4);

-- Backfill. A voucher that is spent (REDEEMED, or a row whose last use is gone
-- — a voucher revoked AFTER its last use keeps status REVOKED with
-- uses_remaining = 0) has nothing left. Every other money voucher still holds
-- its whole face value, because until today a voucher could only ever be
-- spent whole.
--
-- Only MONEY vouchers get a balance. value_type is the unmapped pre-V45
-- column: NULL on every voucher issued since V45 (all money) and 'AMOUNT' on
-- the older money ones. A legacy PERCENT / FREE_ITEM / COMBO row keeps a NULL
-- balance, because "10% off" is not 10 of anything and must never be spent
-- down as if it were; the application spends such a row whole, as before.
-- EXPIRED and REVOKED rows are backfilled too, as history: the column then
-- reads as the balance the voucher held when it stopped being spendable.
-- A pre-V7 row with no face value keeps NULL unless it is already spent.
UPDATE vouchers
SET    remaining_balance = CASE
                               WHEN status = 'REDEEMED' OR uses_remaining <= 0 THEN 0
                               ELSE face_value
                           END
WHERE  remaining_balance IS NULL
  AND  (value_type IS NULL OR value_type = 'AMOUNT')
  AND  (face_value IS NOT NULL OR status = 'REDEEMED' OR uses_remaining <= 0);

-- Never negative, never more than the voucher was worth. A NULL face value
-- (pre-V7 rows) cannot bound the balance, so only the sign is checked there.
ALTER TABLE vouchers DROP CONSTRAINT IF EXISTS chk_vouchers_remaining_balance;
ALTER TABLE vouchers
    ADD CONSTRAINT chk_vouchers_remaining_balance
    CHECK (remaining_balance IS NULL
           OR (remaining_balance >= 0
               AND (face_value IS NULL OR remaining_balance <= face_value)));

-- ---------------------------------------------------------------------------
-- 2. voucher_redemptions carries the money each use moved.
--
--    amount        — money taken off the bill by this use, voucher currency.
--    base_amount   — the USD (base currency) share of the voucher's issue-time
--                    liability (vouchers.base_value) this use consumed. The
--                    per-use figures telescope, so a voucher's uses always sum
--                    to exactly its base_value — never re-converted at today's
--                    rate (V38: a voucher's USD worth is fixed when issued).
--    balance_after — the voucher's remaining balance once this use landed.
--    reference     — the caller's optional idempotency key for the use.
--    first_use     — the voucher's first successful use. A FIXED redeem-side
--                    fee is billed on the first use only; a PERCENTAGE one on
--                    every use's amount.
ALTER TABLE voucher_redemptions ADD COLUMN IF NOT EXISTS amount        NUMERIC(19,4);
ALTER TABLE voucher_redemptions ADD COLUMN IF NOT EXISTS base_amount   NUMERIC(19,4);
ALTER TABLE voucher_redemptions ADD COLUMN IF NOT EXISTS balance_after NUMERIC(19,4);
ALTER TABLE voucher_redemptions ADD COLUMN IF NOT EXISTS reference     VARCHAR(100);
ALTER TABLE voucher_redemptions ADD COLUMN IF NOT EXISTS first_use     BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill the SUCCESS history. Every past use spent the voucher whole (or,
-- for a pre-V49 MULTI_USE voucher, paid its full face value again), so each
-- one moved the face value and its base value — exactly what the invoice run
-- priced it at. balance_after stays NULL: it was never recorded, and a guessed
-- figure would read as a fact. REJECTED rows are refusals and moved nothing.
UPDATE voucher_redemptions r
SET    amount      = v.face_value,
       base_amount = v.base_value
FROM   vouchers v
WHERE  v.id = r.voucher_id
  AND  r.result = 'SUCCESS'
  AND  r.amount IS NULL;

-- The earliest SUCCESS row of each voucher is its first use (id breaks a tie
-- on redeemed_at, so exactly one row per voucher is chosen).
UPDATE voucher_redemptions r
SET    first_use = TRUE
FROM  (SELECT DISTINCT ON (voucher_id) id
       FROM   voucher_redemptions
       WHERE  result = 'SUCCESS'
       ORDER  BY voucher_id, redeemed_at, id) firsts
WHERE  r.id = firsts.id;

-- ---------------------------------------------------------------------------
-- 3. Indexes.
--
-- The idempotency key: one successful use per (merchant, reference). Partial —
-- REJECTED rows never consume a reference, and a use sent without one has
-- nothing to collide on. A concurrent duplicate of the same use is serialised
-- by the voucher row lock before it gets here; this index is what stops the
-- same reference landing on two DIFFERENT vouchers at once.
CREATE UNIQUE INDEX IF NOT EXISTS uq_voucher_redemption_merchant_reference
    ON voucher_redemptions (merchant_id, reference)
    WHERE result = 'SUCCESS' AND reference IS NOT NULL;

-- Invoicing and the merchant reports now bill and count USES in a window:
-- merchant + redeemed_at, successful rows only.
CREATE INDEX IF NOT EXISTS idx_voucher_redemption_merchant_success_at
    ON voucher_redemptions (merchant_id, redeemed_at)
    WHERE result = 'SUCCESS';
