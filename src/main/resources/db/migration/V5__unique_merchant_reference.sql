-- Hardens the duplicate-reference check in TransactionService.post against
-- concurrent inserts. The Java pre-check (findFirstByMerchantIdAndReference)
-- is a TOCTOU: two concurrent POSTs with the same (merchant_id, reference)
-- both pass the SELECT and both INSERT. The unique index makes the DB the
-- final arbiter; the service code catches DataIntegrityViolationException
-- and surfaces it as a 409 DUPLICATE_REFERENCE.
--
-- Partial index (WHERE reference IS NOT NULL) so historical rows with
-- reference=NULL — and the many transactions that legitimately have no
-- merchant-supplied reference (adjustments, reversals before their own
-- "REV-" prefix is set, etc.) — don't collide on NULL.
CREATE UNIQUE INDEX IF NOT EXISTS uq_txn_merchant_reference
    ON loyalty_transactions(merchant_id, reference)
    WHERE reference IS NOT NULL;
