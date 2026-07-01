-- Add CARD_PAYMENT to TransactionType.
--
-- Per the note in V17, every enum gain has to be mirrored by a fresh CHECK
-- constraint or inserts of the new value fail at INSERT time with a check-
-- constraint violation. Four columns reference TransactionType (loyalty_rules,
-- campaigns, loyalty_transactions, qr_tokens) — each gets its constraint
-- dropped and re-added with the expanded allowlist.
--
-- The constraint name MUST match V17's so a future enum gain can do the same
-- DROP+ADD without having to scan for a renamed predecessor.

ALTER TABLE loyalty_rules
    DROP CONSTRAINT IF EXISTS chk_loyalty_rules_transaction_type,
    ADD  CONSTRAINT chk_loyalty_rules_transaction_type
    CHECK (transaction_type IN ('PURCHASE','BILL_PAYMENT','QR_PAY','WALLET_TOPUP','POINTS_PURCHASE','PROMO','REFUND','TRANSFER','REDEMPTION','ADJUSTMENT','CARD_PAYMENT'));

ALTER TABLE campaigns
    DROP CONSTRAINT IF EXISTS chk_campaigns_transaction_type,
    ADD  CONSTRAINT chk_campaigns_transaction_type
    CHECK (transaction_type IN ('PURCHASE','BILL_PAYMENT','QR_PAY','WALLET_TOPUP','POINTS_PURCHASE','PROMO','REFUND','TRANSFER','REDEMPTION','ADJUSTMENT','CARD_PAYMENT'));

ALTER TABLE loyalty_transactions
    DROP CONSTRAINT IF EXISTS chk_loyalty_transactions_type,
    ADD  CONSTRAINT chk_loyalty_transactions_type
    CHECK (type IN ('PURCHASE','BILL_PAYMENT','QR_PAY','WALLET_TOPUP','POINTS_PURCHASE','PROMO','REFUND','TRANSFER','REDEMPTION','ADJUSTMENT','CARD_PAYMENT'));

ALTER TABLE qr_tokens
    DROP CONSTRAINT IF EXISTS chk_qr_tokens_transaction_type,
    ADD  CONSTRAINT chk_qr_tokens_transaction_type
    CHECK (transaction_type IN ('PURCHASE','BILL_PAYMENT','QR_PAY','WALLET_TOPUP','POINTS_PURCHASE','PROMO','REFUND','TRANSFER','REDEMPTION','ADJUSTMENT','CARD_PAYMENT'));
