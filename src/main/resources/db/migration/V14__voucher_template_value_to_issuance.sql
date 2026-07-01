-- The voucher's monetary value (e.g. "$5 off") moves from the template
-- onto the issuance request. A single "Coffee voucher" template can now
-- be issued at $5 or $10 depending on what the cashier picks at the
-- moment of issue, instead of every Coffee voucher being locked to the
-- same face value chained from the template.
--
-- The Voucher (issued instance) keeps its own `face_value` column —
-- that's where the per-issuance amount lives. We're only dropping the
-- column on the template side.
ALTER TABLE voucher_templates DROP COLUMN IF EXISTS face_value;
