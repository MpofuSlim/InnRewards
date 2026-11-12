-- V67: voucher fees are InnBucks' to set (owner decision, 2026-11-12).
--
-- A fee is what a merchant pays InnBucks for its vouchers. Until now it lived on
-- the merchant record and on loyalty_rules, both of which a merchant admin can
-- write, so a merchant could set its own fees, zero included. This table is the
-- platform's own fee schedule, written only by holders of loyalty-fees:manage
-- (or SUPER_ADMIN) through /loyalty/fees/**:
--
--   * merchant_id NULL  = the tenant's default, which every merchant under it pays;
--   * merchant_id set   = that merchant's own fees, which beat the default.
--
-- Resolution (EffectiveFees): the merchant's latest row, then the tenant's latest
-- row, then the legacy rule/record fees exactly as before. A database with no
-- rows here prices every voucher exactly as it did before this migration.
--
-- Append-only: a change is a new row, so the table IS the history (who set what,
-- when). A row with cleared = TRUE is a tombstone: the scope's schedule is
-- withdrawn and resolution falls through to the next level, the same pattern as
-- exchange_rates (V39). Both sides are always set together on a live row, so
-- "which fee applies" never mixes two decisions.

CREATE TABLE fee_schedules (
    id                       UUID          PRIMARY KEY,
    tenant_id                UUID          NOT NULL REFERENCES tenants(id),
    merchant_id              UUID          REFERENCES merchants(id),
    cleared                  BOOLEAN       NOT NULL DEFAULT FALSE,
    fee_issued_type          VARCHAR(30),
    fee_issued_fixed         NUMERIC(19, 6),
    fee_issued_percentage    NUMERIC(7, 4),
    fee_redeemed_type        VARCHAR(30),
    fee_redeemed_fixed       NUMERIC(19, 6),
    fee_redeemed_percentage  NUMERIC(7, 4),
    note                     VARCHAR(500),
    set_by                   VARCHAR(254)  NOT NULL,
    set_by_user_id           UUID,
    created_at               TIMESTAMPTZ   NOT NULL,

    -- A live row sets both sides completely; a tombstone sets nothing.
    CONSTRAINT chk_fee_schedule_shape CHECK (
        (cleared
            AND fee_issued_type IS NULL AND fee_issued_fixed IS NULL AND fee_issued_percentage IS NULL
            AND fee_redeemed_type IS NULL AND fee_redeemed_fixed IS NULL AND fee_redeemed_percentage IS NULL)
        OR (NOT cleared
            AND fee_issued_type IS NOT NULL AND fee_issued_fixed IS NOT NULL
            AND fee_issued_percentage IS NOT NULL
            AND fee_redeemed_type IS NOT NULL AND fee_redeemed_fixed IS NOT NULL
            AND fee_redeemed_percentage IS NOT NULL)),
    CONSTRAINT chk_fee_schedule_types CHECK (
        (fee_issued_type IS NULL OR fee_issued_type IN ('FIXED', 'PERCENTAGE', 'FIXED_PLUS_PERCENTAGE'))
        AND (fee_redeemed_type IS NULL OR fee_redeemed_type IN ('FIXED', 'PERCENTAGE', 'FIXED_PLUS_PERCENTAGE'))),
    CONSTRAINT chk_fee_schedule_values CHECK (
        COALESCE(fee_issued_fixed, 0) >= 0
        AND COALESCE(fee_redeemed_fixed, 0) >= 0
        AND COALESCE(fee_issued_percentage, 0) BETWEEN 0 AND 100
        AND COALESCE(fee_redeemed_percentage, 0) BETWEEN 0 AND 100)
);

-- The resolver reads the latest row per (tenant, merchant) scope.
CREATE INDEX idx_fee_schedules_scope ON fee_schedules (tenant_id, merchant_id, created_at DESC, id DESC);

-- No UPDATE and no DELETE, whoever asks: a fee decision is never rewritten.
CREATE OR REPLACE FUNCTION fee_schedules_are_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'fee_schedules is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_fee_schedules_append_only
    BEFORE UPDATE OR DELETE ON fee_schedules
    FOR EACH ROW EXECUTE FUNCTION fee_schedules_are_append_only();
