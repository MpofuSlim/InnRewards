-- V65: a partner may set a voucher's validity when it generates one.
--
-- The middleware asked to choose how long each voucher stays valid. The order
-- snapshots the requested number of days, because an order paid later (an
-- InnBucks code, a slow funding leg) is issued by the reconciler, not by the
-- request that asked. NULL means the merchant's configured validity, exactly as
-- before. The bounds a request may ask for are configuration
-- (loyalty.voucher-partner.min-validity-days / max-validity-days); the CHECK
-- only refuses what no configuration could mean.
ALTER TABLE voucher_purchase_orders ADD COLUMN IF NOT EXISTS validity_days INTEGER;

ALTER TABLE voucher_purchase_orders ADD CONSTRAINT chk_vpo_validity_days
    CHECK (validity_days IS NULL OR validity_days > 0);
