-- IN-9: link each loyalty transaction to the invoice that billed its period,
-- so the points-issued reports can name the invoice a row was billed on.
--
-- Direction of the link matters: an invoice does NOT generate points. Points
-- generate the invoice — InvoicingService sums loyalty_transactions over the
-- billing window to produce invoices.points_issued / points_redeemed. This
-- column is therefore a back-reference for traceability, stamped when the
-- invoice is generated, not a funding relationship.
--
-- Nullable on purpose. A transaction has no invoice when:
--   * its period hasn't been invoiced yet (the job runs on a cron), or
--   * the merchant had NO billable voucher activity that period. Invoice
--     totals come from voucher fees, not points, and InvoicingService skips
--     zero-total invoices entirely — so points can legitimately exist in a
--     period with no invoice at all. "No invoice" is a real answer here, not
--     missing data.
ALTER TABLE loyalty_transactions
    ADD COLUMN invoice_id UUID;

ALTER TABLE loyalty_transactions
    ADD CONSTRAINT fk_loyalty_txn_invoice
    FOREIGN KEY (invoice_id) REFERENCES invoices (id)
    ON DELETE SET NULL;

-- Drives "which rows did invoice X bill" and the not-yet-invoiced sweep.
CREATE INDEX idx_loyalty_txn_invoice ON loyalty_transactions (invoice_id);

-- Backfill historical rows against invoices that already exist.
--
-- The match mirrors InvoicingService's own window EXACTLY, or the backfilled
-- link would disagree with the figure printed on the invoice:
--   merchant_id, status = POSTED, and created_at in
--   [period_start 00:00 UTC, period_end + 1 day 00:00 UTC)
-- (the service builds those bounds with LocalDate.atStartOfDay().toInstant(UTC);
-- adding 1 to a DATE in Postgres is the same half-open upper bound).
--
-- DISTINCT ON picks the EARLIEST invoice when periods overlap — which can
-- happen if a merchant's billing cycle changed (e.g. DAILY -> MONTHLY) and a
-- day was covered twice. Without it, UPDATE ... FROM would pick an arbitrary
-- match and the backfill would be non-deterministic between environments.
-- Attributing a row to the invoice that billed it first is the honest answer;
-- the overlap itself is a pre-existing invoicing concern, not one this
-- introduces.
UPDATE loyalty_transactions t
   SET invoice_id = pick.invoice_id
  FROM (
        SELECT DISTINCT ON (txn.id)
               txn.id AS txn_id,
               i.id   AS invoice_id
          FROM loyalty_transactions txn
          JOIN invoices i
            ON i.merchant_id = txn.merchant_id
           AND txn.created_at >= (i.period_start::timestamp AT TIME ZONE 'UTC')
           AND txn.created_at <  ((i.period_end + 1)::timestamp AT TIME ZONE 'UTC')
         WHERE txn.status = 'POSTED'
         ORDER BY txn.id, i.period_start, i.created_at
       ) AS pick
 WHERE t.id = pick.txn_id;
