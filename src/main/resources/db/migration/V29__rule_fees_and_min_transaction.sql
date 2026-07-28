-- Rules become the home of the tenant's STANDARD commercial config:
--
-- 1. Voucher fee schedules (issued/redeemed) move up to rule level with the
--    same global/merchant-override inheritance rules already have: a global
--    rule's fees are the tenant standard; a merchant-specific rule overrides
--    them; a merchant that configures nothing inherits the global standard.
--    (The merchant-record fee columns stay for explicit per-merchant
--    onboarding overrides — resolution order lives in EffectiveFees.)
--    All columns NULLABLE: null = "not configured at this level, inherit".
--
-- 2. min_transaction_amount — the earning floor: a transaction strictly below
--    it earns ZERO points. Same inheritance: merchant rule value wins, null
--    falls back to the global rule's value, null there = no floor.
ALTER TABLE loyalty_rules
    ADD COLUMN min_transaction_amount NUMERIC(19, 2),
    ADD COLUMN fee_issued_type        VARCHAR(30),
    ADD COLUMN fee_issued_fixed       NUMERIC(19, 6),
    ADD COLUMN fee_issued_percentage  NUMERIC(7, 4),
    ADD COLUMN fee_redeemed_type      VARCHAR(30),
    ADD COLUMN fee_redeemed_fixed     NUMERIC(19, 6),
    ADD COLUMN fee_redeemed_percentage NUMERIC(7, 4);
