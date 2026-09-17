-- Voucher DELIVERED is merged into ISSUED (owner decision, 2026-09-17).
--
-- WHY. Voucher.Status carried both ISSUED and DELIVERED, and the pair was
-- indistinguishable in practice AND misleading in the one case where it
-- appeared to say something:
--
--   * DELIVERED was stamped SYNCHRONOUSLY in VoucherService.finishIssue, at
--     save time, BEFORE the @Async WhatsApp/SMS send ran — and was never
--     revised. A voucher whose WhatsApp failed AND whose SMS fallback failed
--     still read DELIVERED; so did one with no reachable phone at all. The
--     gateway's own log line for that case says "still issued" while the row
--     said otherwise. So DELIVERED meant "we dispatched", never "the customer
--     got it", and no status ever meant the latter.
--   * Because every voucher issued to a named person carries a delivery
--     channel, that flip was immediate and universal: a single-issued voucher
--     NEVER spent a moment in ISSUED. The operator console's ISSUED tab was
--     permanently empty except for bulk campaign stock (which /issue-bulk
--     never attempts to deliver at all).
--   * Every money and liability aggregation in the service already treated the
--     two as one thing, summing ISSUED + DELIVERED + VIEWED + PARTIALLY_USED
--     as "outstanding".
--
-- So the distinction cost a report tab, a status column and a support question,
-- and answered nothing. The lifecycle is now:
--
--   ISSUED -> VIEWED -> PARTIALLY_USED -> REDEEMED   (+ EXPIRED / REVOKED)
--
-- WHAT IS KEPT. vouchers.delivered_at STAYS and is still stamped at issue. It
-- is the honest, unambiguous version of what DELIVERED was reaching for — the
-- instant dispatch was ATTEMPTED — and unlike the status it makes no claim
-- about arrival. It is also already surfaced on the voucher report row DTO and
-- the report CSV (`deliveredAt`), so dropping it would be a separate,
-- client-visible decision rather than part of this cleanup.
--
-- THE UPDATE BELOW IS NOT HOUSEKEEPING — IT IS WHAT KEEPS THE SERVICE UP.
-- vouchers.status is @Enumerated(EnumType.STRING), so once DELIVERED is gone
-- from the Java enum, Hibernate cannot hydrate a row that still holds the
-- string: it throws IllegalArgumentException per row at query EXECUTION, which
-- GlobalExceptionHandler turns into an opaque 500 ("Something went wrong on our
-- end"). That would hit EVERY voucher read path — the customer wallet, the
-- /loyalty/public/** wallet and voucher lists, findByCode on redeem, and every
-- report — for any cell holding DELIVERED rows.
--
-- Note there is no compile-time, boot-time or CI signal for this: every
-- @SpringBootTest applies Flyway first, so the suite is green either way and
-- the breakage would appear only against a cell with real history. Flyway runs
-- before the pod serves traffic, so a new replica is never exposed to
-- un-migrated rows; and because the surviving value is ISSUED — which the OLD
-- enum also knows — a rolling deploy is safe in both directions and pinning
-- back to the previous image after this migration stays safe too.
--
-- Order matters: the rows must be rewritten BEFORE the CHECK constraint is
-- narrowed, or ADD CONSTRAINT is refused by the surviving DELIVERED rows.

-- 1. Rewrite history. Every DELIVERED row becomes ISSUED — an information-
--    preserving move, because delivered_at (set on exactly these rows) already
--    records the dispatch this status was standing in for.
UPDATE vouchers SET status = 'ISSUED' WHERE status = 'DELIVERED';

-- 2. Narrow the enum guard. V17 declared chk_vouchers_status including
--    'DELIVERED'; drop and re-add it without that value so the database
--    refuses the dead status rather than trusting the application not to write
--    it. IF EXISTS keeps this idempotent against a cell whose V17 predates the
--    constraint.
ALTER TABLE vouchers DROP CONSTRAINT IF EXISTS chk_vouchers_status;
ALTER TABLE vouchers
    ADD CONSTRAINT chk_vouchers_status
    CHECK (status IN ('ISSUED','VIEWED','REDEEMED','PARTIALLY_USED','EXPIRED','REVOKED'));
