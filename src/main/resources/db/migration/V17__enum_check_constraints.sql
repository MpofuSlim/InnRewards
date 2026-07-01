-- Enum integrity: pin every @Enumerated(EnumType.STRING) column to its Java
-- enum's constants with a CHECK constraint. Postgres stores these as plain
-- VARCHAR, so without this the DB will accept ANY string — a typo in app
-- code, a hand-run UPDATE, or a mistyped enum literal in a future migration
-- (exactly the failure mode V16's `type NOT IN (...)` predicate could have
-- hit) all slip through silently. H2 never enforced this either, so the move
-- to real Postgres is the moment to add it. Mirrors the existing pattern in
-- V9 (oradian_sync_transactions.status) and chk_balance_non_negative (V1).
--
-- A CHECK passes on NULL (three-valued logic), so the same `col IN (...)`
-- form is correct for both nullable and NOT NULL columns; NOT NULL is
-- enforced separately by the column definition where it applies.
--
-- NOTE: when an enum gains a constant, the matching constraint must be
-- updated in a new migration (DROP + re-ADD) or inserts of the new value
-- will fail. That coupling is the intended trade-off — the DB stays a
-- faithful mirror of the enum.

-- TransactionType — reused by four columns
ALTER TABLE loyalty_rules
    ADD CONSTRAINT chk_loyalty_rules_transaction_type
    CHECK (transaction_type IN ('PURCHASE','BILL_PAYMENT','QR_PAY','WALLET_TOPUP','POINTS_PURCHASE','PROMO','REFUND','TRANSFER','REDEMPTION','ADJUSTMENT'));

ALTER TABLE campaigns
    ADD CONSTRAINT chk_campaigns_transaction_type
    CHECK (transaction_type IN ('PURCHASE','BILL_PAYMENT','QR_PAY','WALLET_TOPUP','POINTS_PURCHASE','PROMO','REFUND','TRANSFER','REDEMPTION','ADJUSTMENT'));

ALTER TABLE loyalty_transactions
    ADD CONSTRAINT chk_loyalty_transactions_type
    CHECK (type IN ('PURCHASE','BILL_PAYMENT','QR_PAY','WALLET_TOPUP','POINTS_PURCHASE','PROMO','REFUND','TRANSFER','REDEMPTION','ADJUSTMENT'));

ALTER TABLE qr_tokens
    ADD CONSTRAINT chk_qr_tokens_transaction_type
    CHECK (transaction_type IN ('PURCHASE','BILL_PAYMENT','QR_PAY','WALLET_TOPUP','POINTS_PURCHASE','PROMO','REFUND','TRANSFER','REDEMPTION','ADJUSTMENT'));

-- LoyaltyTransaction.Status
ALTER TABLE loyalty_transactions
    ADD CONSTRAINT chk_loyalty_transactions_status
    CHECK (status IN ('POSTED','REVERSED'));

-- Tenant.Status
ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_status
    CHECK (status IN ('ACTIVE','SUSPENDED','INACTIVE'));

-- Merchant.BillingCycle / Merchant.Status
ALTER TABLE merchants
    ADD CONSTRAINT chk_merchants_billing_cycle
    CHECK (billing_cycle IN ('WEEKLY','MONTHLY'));

ALTER TABLE merchants
    ADD CONSTRAINT chk_merchants_status
    CHECK (status IN ('ACTIVE','INACTIVE'));

-- Shop.Status
ALTER TABLE shops
    ADD CONSTRAINT chk_shops_status
    CHECK (status IN ('ACTIVE','INACTIVE'));

-- LoyaltyUser.Role / LoyaltyUser.Status
ALTER TABLE loyalty_users
    ADD CONSTRAINT chk_loyalty_users_role
    CHECK (role IN ('END_USER','MERCHANT_ADMIN','MERCHANT_FINANCE','TENANT_ADMIN','PLATFORM_ADMIN','AUDITOR'));

ALTER TABLE loyalty_users
    ADD CONSTRAINT chk_loyalty_users_status
    CHECK (status IN ('ACTIVE','BLOCKED','INACTIVE','PENDING'));

-- Wallet.Type
ALTER TABLE wallets
    ADD CONSTRAINT chk_wallets_type
    CHECK (type IN ('MAIN','SUB','SAVINGS'));

-- VoucherTemplate.VoucherType (column `type`) / VoucherTemplate.ValueType
ALTER TABLE voucher_templates
    ADD CONSTRAINT chk_voucher_templates_type
    CHECK (type IN ('SINGLE_USE','MULTI_USE','CAMPAIGN','REFERRAL','CORPORATE'));

ALTER TABLE voucher_templates
    ADD CONSTRAINT chk_voucher_templates_value_type
    CHECK (value_type IN ('AMOUNT','PERCENT','FREE_ITEM','COMBO'));

-- Voucher.Status / Voucher.DeliveryChannel / VoucherTemplate.ValueType (snapshot)
ALTER TABLE vouchers
    ADD CONSTRAINT chk_vouchers_status
    CHECK (status IN ('ISSUED','DELIVERED','VIEWED','REDEEMED','PARTIALLY_USED','EXPIRED','REVOKED'));

ALTER TABLE vouchers
    ADD CONSTRAINT chk_vouchers_delivery_channel
    CHECK (delivery_channel IN ('SMS','WHATSAPP','EMAIL','PUSH','POS','NONE'));

ALTER TABLE vouchers
    ADD CONSTRAINT chk_vouchers_value_type
    CHECK (value_type IN ('AMOUNT','PERCENT','FREE_ITEM','COMBO'));

-- VoucherRedemption.Result
ALTER TABLE voucher_redemptions
    ADD CONSTRAINT chk_voucher_redemptions_result
    CHECK (result IN ('SUCCESS','REJECTED'));

-- FraudAttempt.Reason
ALTER TABLE fraud_attempts
    ADD CONSTRAINT chk_fraud_attempts_reason
    CHECK (reason IN ('INVALID_CODE','BAD_SIGNATURE','EXPIRED','ALREADY_REDEEMED','USAGE_EXCEEDED','WRONG_MERCHANT','BLOCKED_DEVICE','BLOCKED_USER','QR_REUSED','QR_EXPIRED','QR_BAD_SIGNATURE'));

-- Invoice.Status
ALTER TABLE invoices
    ADD CONSTRAINT chk_invoices_status
    CHECK (status IN ('PENDING','PAID','OVERDUE','CANCELLED'));

-- QrToken.SourceType
ALTER TABLE qr_tokens
    ADD CONSTRAINT chk_qr_tokens_source_type
    CHECK (source_type IN ('MERCHANT','USER'));
