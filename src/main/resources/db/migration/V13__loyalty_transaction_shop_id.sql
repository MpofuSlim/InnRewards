-- Attach loyalty_transactions rows to the shop that produced them so a
-- SHOP_USER cashier can list "transactions from my outlet" instead of
-- the broader "transactions from my merchant" (which would span every
-- outlet of a chain).
--
-- Nullable because (a) pre-existing rows have no shop attribution to
-- recover and (b) some transaction sources (e.g. admin ADJUSTMENTs,
-- internal P2P transfers, rule-engine accruals not tied to checkout)
-- legitimately have no shop. The /loyalty/transactions/my-shop endpoint
-- ignores those rows.
ALTER TABLE loyalty_transactions
    ADD COLUMN IF NOT EXISTS shop_id UUID;

CREATE INDEX IF NOT EXISTS idx_txn_tenant_shop_created
    ON loyalty_transactions (tenant_id, shop_id, created_at DESC);
