-- V71: a partner sale or redemption may say its channel is API (owner's go,
-- 2026-11-13).
--
-- The InnBucks middleware now fills in "API" when a third-party integrator
-- calls it without a channel. API follows the ONLINE rules: no cash or card,
-- no outlet or terminal, and the voucher code is never in the response. The
-- value is stored as sent, so a report can tell those sales apart.
--
-- Only the two CHECKs change. voucher_redemptions.channel has none, and every
-- channel column is VARCHAR(8). The service's list of channels
-- (PartnerVoucherService.CHANNELS) must name exactly these values;
-- PartnerChannelCheckConstraintTest fails the build when they differ.

ALTER TABLE voucher_purchase_orders DROP CONSTRAINT chk_vpo_channel;
ALTER TABLE voucher_purchase_orders ADD CONSTRAINT chk_vpo_channel
    CHECK (channel IS NULL OR channel IN ('POS', 'ONLINE', 'APP', 'API'));

ALTER TABLE voucher_redeem_payments DROP CONSTRAINT chk_vrp_channel;
ALTER TABLE voucher_redeem_payments ADD CONSTRAINT chk_vrp_channel
    CHECK (channel IN ('POS', 'ONLINE', 'APP', 'API'));
