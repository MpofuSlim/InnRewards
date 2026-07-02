-- Detailed voucher reporting: record WHO issued each voucher and from WHICH
-- shop, so operator/tenant/merchant/shop reports can show a real issuer number
-- (not just the receiver) and attribute a voucher to a specific outlet.
--
-- Captured at issue time from the authenticated caller's JWT (user id, phone,
-- email) plus their shop scope. All nullable + going-forward only: vouchers
-- issued before this migration keep NULLs (their issuer/shop is unknown), and
-- vouchers minted by internal / non-human flows legitimately have no issuer.

ALTER TABLE vouchers ADD COLUMN issuer_user_id uuid;
ALTER TABLE vouchers ADD COLUMN issuer_phone   varchar(32);
ALTER TABLE vouchers ADD COLUMN issuer_email   varchar(200);
ALTER TABLE vouchers ADD COLUMN shop_id        uuid;

-- Shop-level report scans filter by shop_id; issuer drill-downs by issuer_user_id.
CREATE INDEX idx_voucher_shop ON vouchers (shop_id);
CREATE INDEX idx_voucher_issuer_user ON vouchers (issuer_user_id);
