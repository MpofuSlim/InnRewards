-- V70: the partner holding reconciliation (owner's go, 2026-11-13).
--
-- Every run checks the partner voucher money, one row per currency:
--
--   expected in holding  = paid in (Flow 1 sales confirmed into holding)
--                        - settled out (uses paid from holding to the redeeming merchant)
--   accounted for        = unspent balance of those vouchers (expired ones included)
--                        + uses not yet settled (owed to merchants)
--                        + sales paid but whose voucher is not issued yet
--   ledger_difference    = expected - accounted, which must be 0
--
-- When the funding provider can report the holding account's balance,
-- bank_difference = bank_balance - expected. Until the real provider is
-- connected nothing reports it, and both bank columns stay NULL.
--
-- Flow 2 (paid at the redeem, V69) never touches holding and is left out of
-- every figure.
--
-- The table is a record: rows are only ever added.

CREATE TABLE IF NOT EXISTS partner_reconciliation_runs (
    id                    UUID           PRIMARY KEY,
    run_id                UUID           NOT NULL,
    run_at                TIMESTAMPTZ    NOT NULL,
    trigger               VARCHAR(16)    NOT NULL,
    triggered_by          VARCHAR(254),
    currency              VARCHAR(8)     NOT NULL,
    paid_in               NUMERIC(19, 4) NOT NULL,
    settled_out           NUMERIC(19, 4) NOT NULL,
    expected_holding      NUMERIC(19, 4) NOT NULL,
    outstanding_balance   NUMERIC(19, 4) NOT NULL,
    expired_balance       NUMERIC(19, 4) NOT NULL,
    owed_to_merchants     NUMERIC(19, 4) NOT NULL,
    paid_not_issued       NUMERIC(19, 4) NOT NULL,
    ledger_difference     NUMERIC(19, 4) NOT NULL,
    funding_in_flight     NUMERIC(19, 4) NOT NULL,
    bank_balance          NUMERIC(19, 4),
    bank_difference       NUMERIC(19, 4),
    stuck_funding         INTEGER        NOT NULL,
    stuck_settlements     INTEGER        NOT NULL,
    stuck_payments        INTEGER        NOT NULL,
    paid_without_voucher  INTEGER        NOT NULL,
    status                VARCHAR(16)    NOT NULL,
    CONSTRAINT chk_partner_recon_trigger CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT chk_partner_recon_status CHECK (status IN ('BALANCED', 'LEDGER_MISMATCH', 'BANK_MISMATCH')),
    CONSTRAINT chk_partner_recon_bank CHECK ((bank_balance IS NULL) = (bank_difference IS NULL)),
    CONSTRAINT uq_partner_recon_run_currency UNIQUE (run_id, currency)
);

CREATE INDEX IF NOT EXISTS idx_partner_recon_run_at ON partner_reconciliation_runs (run_at DESC);

CREATE OR REPLACE FUNCTION partner_reconciliation_runs_are_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'partner_reconciliation_runs is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_partner_reconciliation_runs_append_only
    BEFORE UPDATE OR DELETE ON partner_reconciliation_runs
    FOR EACH ROW EXECUTE FUNCTION partner_reconciliation_runs_are_append_only();
