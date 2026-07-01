-- Adds the ownership column referenced by Tenant.ownerEmail. Used by
-- TenantContext to validate that the JWT subject matches the tenant's
-- recorded owner (alongside the existing tenant_members membership check
-- and the SUPER_ADMIN bypass).
--
-- Nullable because legacy rows created before this column landed have no
-- recorded owner. The Tenant entity tolerates NULL for the same reason.
-- An index supports the email-keyed lookups in the (rare) admin path that
-- searches tenants by owner email.

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS owner_email VARCHAR(200);

CREATE INDEX IF NOT EXISTS idx_tenant_owner_email ON tenants(owner_email);
