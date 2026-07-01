-- Shops: physical outlets under a Merchant. e.g. "Pizza Inn Avondale" and
-- "Pizza Inn Westgate" are two shops under the "Pizza Inn" merchant. Shops
-- inherit their merchant's loyalty rules transparently — RulesEngine falls
-- through merchant-specific → global tenant rules when a transaction is
-- posted with the shop's merchantId.

CREATE TABLE shops (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    code VARCHAR(40),
    address VARCHAR(300),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shop_tenant ON shops(tenant_id);
CREATE INDEX idx_shop_merchant ON shops(merchant_id);
CREATE INDEX idx_shop_tenant_merchant ON shops(tenant_id, merchant_id);
