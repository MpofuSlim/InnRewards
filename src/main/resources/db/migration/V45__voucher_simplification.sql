-- Voucher simplification (owner decision, 2026-09-17):
--
--   1. Voucher TEMPLATES are retired. Vouchers are issued directly with a
--      type, a money value and a currency — no template in between. The
--      voucher_templates table is left in place, dormant, the same call as
--      event_outbox / the Oradian columns: applied migrations are never
--      edited, unmapped tables are harmless under ddl-auto: validate, and the
--      existing rows are real history (every pre-V45 voucher's template_id
--      points at one). No write path touches it after this migration.
--
--   2. Voucher EXPIRY becomes commercial config on loyalty_rules, with the
--      same two-tier inheritance as the earning floor and the fee schedules:
--      the tenant's GLOBAL rule sets the standard validity, a merchant's own
--      rule overrides it, and a merchant with neither inherits the platform
--      default (loyalty.voucher.default-validity-days).
--
--   3. Value types are gone — a voucher's value is always a money AMOUNT in
--      an explicit currency. PERCENT / FREE_ITEM / COMBO are no longer
--      issuable. vouchers.value_type stays for the pre-V45 rows that carry
--      one (it was already nullable, and its CHECK passes NULL), but new rows
--      never write it.
--
--   4. Voucher types narrow to SINGLE_USE and MULTI_USE. The type now lives
--      on the VOUCHER (it used to live only on the template), stamped at
--      issue time.

-- 1a. A voucher no longer needs a template. Legacy rows keep theirs — the FK
--     stays for their referential integrity; new rows are simply NULL.
ALTER TABLE vouchers ALTER COLUMN template_id DROP NOT NULL;
ALTER TABLE voucher_batches ALTER COLUMN template_id DROP NOT NULL;

-- 4a. The voucher's own type. NULL is allowed ONLY because pre-V45 rows are
--     backfilled best-effort below; the application always stamps it.
ALTER TABLE vouchers ADD COLUMN voucher_type VARCHAR(20);
ALTER TABLE vouchers
    ADD CONSTRAINT chk_vouchers_voucher_type
    CHECK (voucher_type IS NULL OR voucher_type IN ('SINGLE_USE', 'MULTI_USE'));

-- 4b. Backfill legacy rows from their template's usage limit — the honest
--     derivation: CAMPAIGN / REFERRAL / CORPORATE were distribution labels,
--     not redemption semantics; how many times a voucher could be used was
--     always usage_limit. A limit above 1 is a multi-use voucher.
UPDATE vouchers v
SET    voucher_type = CASE WHEN t.usage_limit > 1 THEN 'MULTI_USE' ELSE 'SINGLE_USE' END
FROM   voucher_templates t
WHERE  v.template_id = t.id
  AND  v.voucher_type IS NULL;

-- 2a. Rule-level voucher validity (days from issue until expiry). NULL means
--     "not configured at this level, inherit" — the same convention as
--     min_transaction_amount and the fee columns (V29).
ALTER TABLE loyalty_rules ADD COLUMN voucher_validity_days INTEGER;
ALTER TABLE loyalty_rules
    ADD CONSTRAINT chk_rules_voucher_validity_days
    CHECK (voucher_validity_days IS NULL OR voucher_validity_days > 0);
