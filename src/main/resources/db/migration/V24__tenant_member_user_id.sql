-- Tenant membership keyed by the user's stable cross-service UUID.
--
-- Folds tenant "join" into registration (POST /loyalty/tenants now takes the
-- user's UUID and attaches them as the first member in one call) and moves the
-- TenantContext access check to be user-UUID based, with a backward-compatible
-- email fallback for rows created before this migration.
--
-- Postgres treats multiple NULLs in a UNIQUE index as distinct, so legacy
-- email-only rows (user_id IS NULL) all coexist under the new
-- (tenant_id, user_id) constraint without conflict.

ALTER TABLE tenant_members ADD COLUMN user_id uuid;

CREATE UNIQUE INDEX uk_tenant_member_tenant_user
    ON tenant_members (tenant_id, user_id);

-- Membership is now user_id-keyed; legacy rows may have only an email, and new
-- rows created at registration have only a user_id. Drop the NOT NULL so both
-- shapes are valid. The (tenant_id, email) unique constraint stays — it still
-- guards against duplicate legacy email rows.
ALTER TABLE tenant_members ALTER COLUMN email DROP NOT NULL;
