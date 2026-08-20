-- Points no longer expire.
--
-- Points carried a 30-day per-lot expiry (V22) and the unspent remainder of an
-- expired lot was released to the ledger as breakage. The product decision is
-- now that points do not expire at all.
--
-- "Never expires" is modelled as expires_at IS NULL rather than a far-future
-- sentinel date: NULL is the honest representation of "no expiry", it makes the
-- existing sweep queries skip the row for free (SQL three-valued logic — NULL
-- <= now() is UNKNOWN, never true), and it leaves the mechanism intact so an
-- operator can re-enable expiry per cell with LOYALTY_POINTS_EXPIRY_DAYS.

ALTER TABLE point_lot ALTER COLUMN expires_at DROP NOT NULL;

-- Retire the expiry on every point a customer still HOLDS, not just on points
-- earned from here on. Without this, anything earned in the 30 days before this
-- deploy would still expire — which is exactly what we are telling customers no
-- longer happens.
--
-- Scoped to remaining_amount > 0 (points still on the books) and deliberately
-- NOT limited to expires_at > now(): a lot whose timestamp has already passed
-- but which the hourly sweep has not released yet is still counted in the
-- wallet's balance, so the customer still has those points and they must stop
-- expiring too.
--
-- Lots with remaining_amount = 0 are left untouched — they are history (spent,
-- or already released as breakage with a matching ledger entry). Reversing past
-- breakage would mean crediting balances back, which is a separate decision and
-- would need its own ledger entries rather than a silent UPDATE here.
UPDATE point_lot
   SET expires_at = NULL
 WHERE remaining_amount > 0
   AND expires_at IS NOT NULL;
