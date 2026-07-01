-- Narrow uq_txn_merchant_reference so it only dedups merchant earn/spend
-- SUBMISSIONS — the path it was actually built for (TransactionService.post,
-- see V5). The original V5 index covered (merchant_id, reference) across
-- ALL transaction types, which wrongly rejects two legitimate cases that
-- reuse a reference:
--
--   * TRANSFER — TransferService writes TWO ledger rows (debit + credit)
--     for one P2P transfer, both carrying the same `reason` as reference
--     and the same merchant. The second row collided. This broke every
--     transfer that carried a non-null reason in production (H2 tests
--     never had this index, so it went unnoticed until the test DB moved
--     to real Postgres).
--   * ADJUSTMENT — adjust() stores the admin reason as the reference;
--     two "Goodwill credit" adjustments to different customers at the
--     same merchant would collide. (Reversals are also type ADJUSTMENT
--     but prefix "REV-", so they were already distinct.)
--
-- The earn/spend submission types (PURCHASE, QR_PAY, POINTS_PURCHASE,
-- REDEMPTION, REFUND) keep their duplicate-reference protection — that's
-- the merchant-idempotency guarantee DuplicateTransactionReferenceIT
-- relies on.
--
-- The new index is strictly less restrictive than V5's, so no existing
-- row can violate it (DROP + CREATE is safe with data present).

DROP INDEX IF EXISTS uq_txn_merchant_reference;

CREATE UNIQUE INDEX uq_txn_merchant_reference
    ON loyalty_transactions (merchant_id, reference)
    WHERE reference IS NOT NULL
      AND type NOT IN ('TRANSFER', 'ADJUSTMENT');
