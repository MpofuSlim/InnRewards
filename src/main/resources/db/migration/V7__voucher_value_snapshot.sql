-- Snapshot voucher value/value_type/currency onto each Voucher row at issuance.
--
-- Background: face_value/value_type/currency lived only on voucher_templates,
-- so the wallet response had to either look up the template (extra query, and
-- silently re-prices issued vouchers when the template is edited) or omit the
-- value entirely. We now copy these three fields onto the voucher itself at
-- issuance time. Future template edits leave already-issued vouchers alone —
-- the same semantics as an invoice line vs the price list it was drawn from.
--
-- The columns are nullable for backward compatibility with rows issued before
-- this migration. New issuance always populates them (see
-- VoucherService#createFromTemplate). The backfill below seeds historic rows
-- from their template so the API doesn't return NULLs for legacy data.

ALTER TABLE vouchers
    ADD COLUMN IF NOT EXISTS value_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS face_value NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS currency   VARCHAR(8);

UPDATE vouchers v
SET    value_type = vt.value_type,
       face_value = vt.face_value,
       currency   = vt.currency
FROM   voucher_templates vt
WHERE  v.template_id = vt.id
  AND  v.value_type IS NULL;
