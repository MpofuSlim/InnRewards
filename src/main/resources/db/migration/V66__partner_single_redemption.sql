-- V66: a partner voucher may be "SINGLE" use: spent in full in one redemption.
--
-- Every voucher is a balance voucher (V61): it can be spent over several uses
-- until its balance reaches zero. The middleware asked to mark some vouchers as
-- usable only once (a voucher that funds exactly one bet). Owner decision,
-- 2026-11-11: such a voucher must be redeemed for its whole remaining value in
-- one go, and a smaller amount is REFUSED (400 PARTIAL_USE_NOT_ALLOWED) rather
-- than spending part and forfeiting the rest, so no held customer money is ever
-- left behind on a spent voucher.
--
-- The order carries the request's choice (an order paid later is issued by the
-- reconciler) and the voucher carries it for redemption. FALSE on every
-- existing row: they keep today's behaviour.
ALTER TABLE voucher_purchase_orders
    ADD COLUMN IF NOT EXISTS single_redemption BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE vouchers
    ADD COLUMN IF NOT EXISTS single_redemption BOOLEAN NOT NULL DEFAULT FALSE;
