-- Loyalty & Voucher Management Platform schema (V1)

CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE merchants (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(80),
    location VARCHAR(200),
    currency VARCHAR(8) NOT NULL DEFAULT 'USD',
    billing_cycle VARCHAR(20) NOT NULL DEFAULT 'MONTHLY',
    fee_per_point_issued NUMERIC(19,6) NOT NULL DEFAULT 0,
    fee_per_voucher_issued NUMERIC(19,6) NOT NULL DEFAULT 0,
    fee_per_voucher_redeemed NUMERIC(19,6) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_merchant_tenant ON merchants(tenant_id);

-- Loyalty-side projection of a customer. Identity (name, email, nationalId)
-- lives in user-service; this table only stores the foreign reference
-- (phone_number) plus loyalty-specific columns (role, status, merchant_id).
CREATE TABLE loyalty_users (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    merchant_id UUID REFERENCES merchants(id) ON DELETE SET NULL,
    phone_number VARCHAR(32) NOT NULL,
    role VARCHAR(30) NOT NULL DEFAULT 'END_USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_user_tenant_phone UNIQUE (tenant_id, phone_number)
);
CREATE INDEX idx_user_tenant ON loyalty_users(tenant_id);

CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES loyalty_users(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    label VARCHAR(80) NOT NULL DEFAULT 'Main',
    type VARCHAR(20) NOT NULL DEFAULT 'MAIN',
    pocket VARCHAR(40),
    version BIGINT NOT NULL DEFAULT 0,
    balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    locked_until DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
);
CREATE INDEX idx_wallet_user ON wallets(user_id);

CREATE TABLE loyalty_rules (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    merchant_id UUID REFERENCES merchants(id) ON DELETE CASCADE,
    transaction_type VARCHAR(30) NOT NULL,
    points_per_unit NUMERIC(19,6) NOT NULL DEFAULT 1,
    multiplier NUMERIC(19,4) NOT NULL DEFAULT 1,
    max_points_per_txn NUMERIC(19,4),
    pocket VARCHAR(40),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    starts_at TIMESTAMP WITH TIME ZONE,
    ends_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rule_tenant_merchant ON loyalty_rules(tenant_id, merchant_id);

CREATE TABLE campaigns (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    merchant_id UUID REFERENCES merchants(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    multiplier NUMERIC(19,4) NOT NULL DEFAULT 1,
    transaction_type VARCHAR(30),
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    matched_transactions BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_campaign_tenant ON campaigns(tenant_id);

CREATE TABLE loyalty_transactions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    user_id UUID NOT NULL REFERENCES loyalty_users(id),
    type VARCHAR(30) NOT NULL,
    amount NUMERIC(19,4),
    currency VARCHAR(8) NOT NULL DEFAULT 'USD',
    points_delta NUMERIC(19,4) NOT NULL DEFAULT 0,
    rule_id UUID,
    campaign_id UUID,
    reference VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'POSTED',
    reverses_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_txn_tenant_merchant ON loyalty_transactions(tenant_id, merchant_id);
CREATE INDEX idx_txn_user ON loyalty_transactions(user_id);
CREATE INDEX idx_txn_reference ON loyalty_transactions(reference);
CREATE INDEX idx_txn_created_at ON loyalty_transactions(created_at);

CREATE TABLE points_ledger (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    wallet_id UUID NOT NULL REFERENCES wallets(id) ON DELETE CASCADE,
    transaction_id UUID,
    delta NUMERIC(19,4) NOT NULL,
    balance_after NUMERIC(19,4) NOT NULL,
    reason VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ledger_wallet ON points_ledger(wallet_id);
CREATE INDEX idx_ledger_txn ON points_ledger(transaction_id);

CREATE TABLE voucher_templates (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    merchant_id UUID REFERENCES merchants(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL DEFAULT 'SINGLE_USE',
    value_type VARCHAR(20) NOT NULL DEFAULT 'AMOUNT',
    face_value NUMERIC(19,4),
    currency VARCHAR(8) NOT NULL DEFAULT 'USD',
    free_item_sku VARCHAR(80),
    usage_limit INTEGER NOT NULL DEFAULT 1,
    validity_days INTEGER,
    applicable_outlets VARCHAR(1000),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_voucher_tpl_tenant ON voucher_templates(tenant_id);

CREATE TABLE voucher_batches (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    template_id UUID NOT NULL REFERENCES voucher_templates(id) ON DELETE CASCADE,
    quantity INTEGER NOT NULL,
    campaign VARCHAR(200),
    created_by_user_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_batch_template ON voucher_batches(template_id);

CREATE TABLE vouchers (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    merchant_id UUID,
    template_id UUID NOT NULL REFERENCES voucher_templates(id),
    batch_id UUID REFERENCES voucher_batches(id),
    code VARCHAR(64) NOT NULL UNIQUE,
    signature VARCHAR(128) NOT NULL,
    assigned_user_id UUID,
    assignee_phone VARCHAR(32),
    assignee_name VARCHAR(200),
    status VARCHAR(20) NOT NULL DEFAULT 'ISSUED',
    delivery_channel VARCHAR(20),
    uses_remaining INTEGER NOT NULL DEFAULT 1,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMP WITH TIME ZONE,
    viewed_at TIMESTAMP WITH TIME ZONE,
    redeemed_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    campaign_source VARCHAR(200),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_voucher_tenant ON vouchers(tenant_id);
CREATE INDEX idx_voucher_assignee ON vouchers(assigned_user_id);
CREATE INDEX idx_voucher_status ON vouchers(status);
CREATE INDEX idx_voucher_expires_at ON vouchers(expires_at);

CREATE TABLE voucher_redemptions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    voucher_id UUID NOT NULL REFERENCES vouchers(id) ON DELETE CASCADE,
    user_id UUID,
    merchant_id UUID NOT NULL,
    outlet_code VARCHAR(80),
    redeemed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    result VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    ip_address VARCHAR(64),
    device_fingerprint VARCHAR(128),
    reason VARCHAR(200)
);
CREATE INDEX idx_redemption_voucher ON voucher_redemptions(voucher_id);
CREATE INDEX idx_redemption_user ON voucher_redemptions(user_id);
CREATE INDEX idx_redemption_merchant ON voucher_redemptions(merchant_id);

CREATE TABLE fraud_attempts (
    id UUID PRIMARY KEY,
    tenant_id UUID,
    voucher_code VARCHAR(64),
    user_id UUID,
    merchant_id UUID,
    device_fingerprint VARCHAR(128),
    ip_address VARCHAR(64),
    reason VARCHAR(30) NOT NULL,
    detail VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_fraud_voucher_code ON fraud_attempts(voucher_code);
CREATE INDEX idx_fraud_device ON fraud_attempts(device_fingerprint);
CREATE INDEX idx_fraud_created_at ON fraud_attempts(created_at);

CREATE TABLE invoices (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    invoice_number VARCHAR(40) NOT NULL UNIQUE,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    points_issued NUMERIC(19,4) NOT NULL DEFAULT 0,
    points_redeemed NUMERIC(19,4) NOT NULL DEFAULT 0,
    vouchers_issued BIGINT NOT NULL DEFAULT 0,
    vouchers_redeemed BIGINT NOT NULL DEFAULT 0,
    total_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency VARCHAR(8) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_invoice_merchant ON invoices(merchant_id);
CREATE INDEX idx_invoice_status ON invoices(status);

CREATE TABLE qr_tokens (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    source_id UUID NOT NULL,
    transaction_type VARCHAR(30) NOT NULL,
    amount NUMERIC(19,4),
    currency VARCHAR(8) NOT NULL DEFAULT 'USD',
    token VARCHAR(64) NOT NULL UNIQUE,
    signature VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_qr_tenant ON qr_tokens(tenant_id);
CREATE INDEX idx_qr_expires ON qr_tokens(expires_at);

CREATE TABLE mini_apps (
    id UUID PRIMARY KEY,
    tenant_id UUID,
    merchant_id UUID,
    slug VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    icon_url VARCHAR(500),
    entry_url VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_miniapp_tenant ON mini_apps(tenant_id);
