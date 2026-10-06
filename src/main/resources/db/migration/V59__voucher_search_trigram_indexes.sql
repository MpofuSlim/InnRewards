-- Trigram indexes for the voucher report's text search (VoucherReportFilters
-- q / issuedBy / phone, built in ReportingService.reportFilters).
--
-- Every one of those filters is a contains or ends-with LIKE ('%term%',
-- '%tail', ESCAPE '\'), which a B-tree cannot serve — not even the V55
-- phone indexes, which only help an exact or prefix match. So each search was
-- a scan of every voucher in scope. A GIN index with pg_trgm's gin_trgm_ops
-- answers LIKE with a leading wildcard.
--
-- The indexed expressions are EXACTLY the ones the query runs LIKE over,
-- listed in VoucherSearchColumn: lower(...) where the filter lowers the column
-- before matching (names, issuer email), the bare column where it does not
-- (code, phones). An index on a different expression is never used.
-- VoucherSearchIndexTest pins the two lists to each other.
--
-- The filters OR several columns together. Postgres combines the per-column
-- index scans with a BitmapOr only when EVERY branch is indexed, so all seven
-- are needed for any of them to matter.
--
-- pg_trgm ships with the server (contrib) and is a TRUSTED extension since
-- PostgreSQL 13: a role with CREATE on this database can install it, no
-- superuser needed. No earlier migration created it.
--
-- Plain CREATE INDEX (not CONCURRENTLY), like V57: Flyway runs this file in a
-- transaction, where CONCURRENTLY is not allowed. Each build holds a SHARE
-- lock on vouchers until the migration commits — reads carry on, voucher
-- writes (issue, redeem, view) wait. At the cell's row counts that is well
-- under a second; on a much larger table, build these CONCURRENTLY by hand
-- first (IF NOT EXISTS then makes this file a no-op).

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- q: recipient / sender name, issuer email (lowered); issuedBy: issuer email.
CREATE INDEX IF NOT EXISTS idx_voucher_trgm_assignee_name
    ON vouchers USING gin (lower(assignee_name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_voucher_trgm_sender_name
    ON vouchers USING gin (lower(sender_name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_voucher_trgm_issuer_email
    ON vouchers USING gin (lower(issuer_email) gin_trgm_ops);

-- q: part of the voucher code (stored raw, upper-case — matched as is).
CREATE INDEX IF NOT EXISTS idx_voucher_trgm_code
    ON vouchers USING gin (code gin_trgm_ops);

-- q (contains) and phone (ends-with): recipient and sender phones.
CREATE INDEX IF NOT EXISTS idx_voucher_trgm_assignee_phone
    ON vouchers USING gin (assignee_phone gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_voucher_trgm_sender_phone
    ON vouchers USING gin (sender_phone gin_trgm_ops);

-- issuedBy: part of the issuing staff member's phone.
CREATE INDEX IF NOT EXISTS idx_voucher_trgm_issuer_phone
    ON vouchers USING gin (issuer_phone gin_trgm_ops);
