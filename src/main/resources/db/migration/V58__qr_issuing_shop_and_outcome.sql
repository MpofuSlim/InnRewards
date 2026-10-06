-- QR tokens remember WHERE they were issued and WHAT their consume produced.
--
-- shop_id: the issuing caller's token shopId claim (SHOP_ADMIN at a till), so
-- the earn a merchant QR produces is attributed to that outlet. The earn used
-- to take the shop from the SCANNING caller's token — a customer, who has none
-- — so every QR earn landed with shop_id NULL and never showed up in the till's
-- /loyalty/transactions/my-shop feed or the per-shop points report.
--
-- transaction_id / points_awarded: what consume produced, read back by
-- POST /loyalty/qr/status so the till can tell the QR it showed was scanned
-- and for how many points. transaction_id is the earn row for a MERCHANT QR
-- and NULL for a P2P transfer QR (the transfer returns no single row);
-- points_awarded is the earn's points delta, or the points moved by a
-- transfer QR.
--
-- All nullable, no backfill: rows issued or consumed before this migration
-- keep NULL, which the status read reports as "not recorded", never as zero.
-- Additive, so the previous build ignores the columns on rollback.
ALTER TABLE qr_tokens ADD COLUMN IF NOT EXISTS shop_id        UUID;
ALTER TABLE qr_tokens ADD COLUMN IF NOT EXISTS transaction_id UUID;
ALTER TABLE qr_tokens ADD COLUMN IF NOT EXISTS points_awarded NUMERIC(19,4);
