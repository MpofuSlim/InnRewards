-- Allow DAILY as a merchant billing cycle, alongside WEEKLY and MONTHLY.
--
-- The invoice scheduler (InvoiceScheduler) already runs daily, and
-- InvoicingService now computes a single-day (yesterday) period for DAILY
-- merchants, so a DAILY merchant is invoiced for each completed day.
--
-- V17 added:  chk_merchants_billing_cycle  CHECK (billing_cycle IN ('WEEKLY','MONTHLY')).
-- Flyway validates checksums, so V17 must stay immutable; widen the constraint
-- here in a new migration (drop + re-add) rather than editing V17.
ALTER TABLE merchants DROP CONSTRAINT IF EXISTS chk_merchants_billing_cycle;
ALTER TABLE merchants
    ADD CONSTRAINT chk_merchants_billing_cycle
    CHECK (billing_cycle IN ('WEEKLY', 'MONTHLY', 'DAILY'));
