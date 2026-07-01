-- Remove the per-point fee column. Innbucks does not charge merchants
-- per loyalty point issued — the only fees on the schedule are
-- per-voucher-issued and per-voucher-redeemed.
ALTER TABLE merchants DROP COLUMN IF EXISTS fee_per_point_issued;
