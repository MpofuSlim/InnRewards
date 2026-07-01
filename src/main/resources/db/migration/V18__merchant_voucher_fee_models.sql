-- Change the per-voucher merchant fee from a single flat amount into a
-- three-mode model. Each side (issued / redeemed) is configured
-- independently so a merchant can charge, say, a flat fee on issue plus a
-- percentage on redeem.
--
-- Modes (stored in fee_*_type):
--   FIXED                  fee = fee_*_fixed       (the legacy behaviour)
--   PERCENTAGE             fee = voucher_face_value * fee_*_percentage / 100
--   FIXED_PLUS_PERCENTAGE  fee = fee_*_fixed + (voucher_face_value * fee_*_percentage / 100)
--
-- fee_*_percentage is stored as a whole-number percent (e.g. 2.5 means 2.5%)
-- to match what merchants see and type in the admin UI. The /100 lives in
-- code, not in the column.
--
-- Backward compatibility: every existing row was running the legacy
-- count*flat formula, so we backfill type=FIXED with the old flat amount,
-- then drop the legacy columns.

ALTER TABLE merchants
    ADD COLUMN fee_issued_type         VARCHAR(30)    NOT NULL DEFAULT 'FIXED',
    ADD COLUMN fee_issued_fixed        NUMERIC(19,6)  NOT NULL DEFAULT 0,
    ADD COLUMN fee_issued_percentage   NUMERIC(7,4)   NOT NULL DEFAULT 0,
    ADD COLUMN fee_redeemed_type       VARCHAR(30)    NOT NULL DEFAULT 'FIXED',
    ADD COLUMN fee_redeemed_fixed      NUMERIC(19,6)  NOT NULL DEFAULT 0,
    ADD COLUMN fee_redeemed_percentage NUMERIC(7,4)   NOT NULL DEFAULT 0;

UPDATE merchants
SET fee_issued_fixed   = fee_per_voucher_issued,
    fee_redeemed_fixed = fee_per_voucher_redeemed;

ALTER TABLE merchants
    DROP COLUMN fee_per_voucher_issued,
    DROP COLUMN fee_per_voucher_redeemed;

-- Enum + non-negative guards. Cross-field "type=PERCENTAGE => fixed=0" is
-- enforced at the service layer rather than via a CHECK so the validation
-- error message can name the offending field; the DB guard would surface
-- as a generic constraint violation that the API can't translate cleanly.
ALTER TABLE merchants
    ADD CONSTRAINT chk_merchants_fee_issued_type
        CHECK (fee_issued_type IN ('FIXED', 'PERCENTAGE', 'FIXED_PLUS_PERCENTAGE')),
    ADD CONSTRAINT chk_merchants_fee_redeemed_type
        CHECK (fee_redeemed_type IN ('FIXED', 'PERCENTAGE', 'FIXED_PLUS_PERCENTAGE')),
    ADD CONSTRAINT chk_merchants_fee_issued_nonneg
        CHECK (fee_issued_fixed >= 0 AND fee_issued_percentage >= 0),
    ADD CONSTRAINT chk_merchants_fee_redeemed_nonneg
        CHECK (fee_redeemed_fixed >= 0 AND fee_redeemed_percentage >= 0);
