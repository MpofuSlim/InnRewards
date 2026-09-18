-- V49 — collapse the outstanding MULTI_USE vouchers.
--
-- Owner decision (2026-09-18), completing the retirement that landed with V48's
-- release: a voucher is worth its face value and is redeemed ONCE.
-- POST /loyalty/vouchers/issue already refuses MULTI_USE
-- (MULTI_USE_RETIRED), but refusing to mint new ones does nothing about the
-- rows already in the table: a live MULTI_USE voucher keeps handing a till its
-- FULL face value on every remaining use, because `face_value` is the amount
-- and `uses_remaining` is only a counter — there is no per-use remainder
-- anywhere. So a $5 voucher with 3 uses is a $15 liability that the retirement
-- was meant to end. Refusing the issue and leaving the stock is half a
-- retirement, and the half that does not hold the money.
--
-- WHAT THIS DOES
--   1. Every LIVE MULTI_USE voucher keeps exactly ONE use.
--   2. Every MULTI_USE row becomes SINGLE_USE, so no row in the table holds the
--      retired value any more.
--   3. The CHECK constraint stops narrows to SINGLE_USE, so nothing can write
--      it again.
--
-- The holder of a PART-USED voucher keeps their one remaining use like everyone
-- else, rather than being treated as already finished. That is the operator's
-- call — the cell is in test phase, so no real customer position is affected —
-- and it is the kinder of the two readings: nobody's voucher dies in their hand.
-- The cost, stated plainly, is that a part-used voucher will have paid out its
-- face value twice across the two regimes.
--
-- Nobody is compensated for uses they lose. Collapsing the uses INTO the value
-- (a 3-use $5 voucher becoming one $10 voucher) was considered and rejected: it
-- mints face values nobody ever issued, moves the outstanding liability, and
-- would have to re-freeze base_value and fx_rate_id at today's rate — where
-- V38's whole point is that a voucher's USD worth is fixed at the moment the
-- promise was made.

-- ---------------------------------------------------------------------------
-- 1. One use each, for the live ones only.
--
--    LIVE is ISSUED / VIEWED / PARTIALLY_USED — `Voucher.LIVE_STATUSES`, the
--    one definition of an outstanding voucher (V48). Keep this list in
--    lock-step with that constant; VoucherLiveStatusMigrationTest reads the
--    names straight out of this file and fails the commit that changes one
--    without the other.
--
--    Terminal rows (REDEEMED, EXPIRED, REVOKED) keep their uses_remaining
--    untouched. It is history: a REDEEMED row already sits at 0, and rewriting
--    an EXPIRED or REVOKED row's counter would restate what was true when it
--    stopped being spendable, for a column nothing will read again.
--
--    `uses_remaining > 1` rather than `<> 1` so a MULTI_USE row someone had
--    already drawn down to its last use is left exactly as it is — this
--    migration only ever takes uses away, never hands one back.
UPDATE vouchers
SET    uses_remaining = 1
WHERE  voucher_type = 'MULTI_USE'
  AND  status IN ('ISSUED', 'VIEWED', 'PARTIALLY_USED')
  AND  uses_remaining > 1;

-- ---------------------------------------------------------------------------
-- 2. Retype every row, terminal ones included.
--
--    This runs BEFORE the CHECK is narrowed, and that order is the rule this
--    repo learned the hard way: removing a value from an
--    @Enumerated(EnumType.STRING) enum is a DATA migration, and a row holding a
--    string the constraint or the Java enum no longer accepts fails at query
--    EXECUTION, per row, with no compile, boot or CI signal — every
--    @SpringBootTest applies Flyway first, so the suite stays green and the
--    breakage appears only against a cell with real history.
--
--    Terminal rows are retyped too, which is the one place this migration does
--    rewrite history: `voucher_type` on a spent voucher becomes SINGLE_USE
--    though it was issued MULTI_USE. That is deliberate — the column describes
--    redemption semantics that no longer exist, `uses_remaining` still records
--    what actually happened, and leaving the value behind would mean the
--    constraint below could never be narrowed.
UPDATE vouchers
SET    voucher_type = 'SINGLE_USE'
WHERE  voucher_type = 'MULTI_USE';

-- ---------------------------------------------------------------------------
-- 3. Narrow the constraint.
--
--    V45 allowed ('SINGLE_USE', 'MULTI_USE'). With no row holding the retired
--    value, the database can now enforce what the code already refuses, which
--    turns "the service rejects it" into "it cannot be stored".
--
--    NULL stays allowed: V45 added the column nullable and backfilled legacy
--    rows best-effort from their template's usage limit, so a pre-V45 voucher
--    whose template row was missing has a NULL type to this day. Rejecting NULL
--    here would fail this migration on exactly those rows.
--
--    Reversal cost, since this is the tightening: bringing MULTI_USE back needs
--    a migration to widen the CHECK again, on top of the code change. The Java
--    constant is kept for a different reason — see Voucher.VoucherType.
ALTER TABLE vouchers DROP CONSTRAINT IF EXISTS chk_vouchers_voucher_type;
ALTER TABLE vouchers
    ADD CONSTRAINT chk_vouchers_voucher_type
    CHECK (voucher_type IS NULL OR voucher_type = 'SINGLE_USE');
