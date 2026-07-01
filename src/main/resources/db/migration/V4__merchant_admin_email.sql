-- Records which user-service identity administers each loyalty merchant.
-- Populated at POST /loyalty/merchants by reading the JWT subject. The
-- value is consumed by user-service's AuthService at login as a fallback
-- when TenantProfile.loyaltyMerchantId is null — the result is cached back
-- to TenantProfile so subsequent logins skip the cross-service call.
ALTER TABLE merchants
    ADD COLUMN IF NOT EXISTS admin_email VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_merchant_admin_email ON merchants(admin_email);
