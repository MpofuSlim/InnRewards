-- Indexes for nightly invoicing and the merchant dashboards, from the
-- optimization-checklist audit. Each serves a merchant + time-window query
-- that could only use idx_voucher_tenant / idx_txn_tenant_merchant and then
-- filter every row of the tenant.
--
-- Plain CREATE INDEX (not CONCURRENTLY): Flyway runs this in a transaction,
-- and at today's row counts the brief write lock is milliseconds.

-- VoucherRepository.countBy/findByMerchantIdAndIssuedAtBetween — invoicing's
-- issue-fee lines and the dashboard's issued counts.
CREATE INDEX IF NOT EXISTS idx_voucher_merchant_issued_at
    ON vouchers (merchant_id, issued_at);

-- ...AndRedeemedAtBetween — the redeem-fee lines. Partial: most vouchers are
-- never redeemed, and a BETWEEN on the column already implies NOT NULL, so
-- the planner can use it for every one of those queries.
CREATE INDEX IF NOT EXISTS idx_voucher_merchant_redeemed_at
    ON vouchers (merchant_id, redeemed_at)
    WHERE redeemed_at IS NOT NULL;

-- LoyaltyTransactionRepository.sumPointsIssued / sumPointsRedeemed /
-- stampInvoice (merchant + created_at window + status POSTED) and the
-- per-customer sums (user + created_at window). Not partial on status: the
-- JPQL renders status as a bind in some paths, which a partial index cannot
-- be proved against, and nearly every row is POSTED anyway.
CREATE INDEX IF NOT EXISTS idx_txn_merchant_created_at
    ON loyalty_transactions (merchant_id, created_at);

CREATE INDEX IF NOT EXISTS idx_txn_user_created_at
    ON loyalty_transactions (user_id, created_at);
