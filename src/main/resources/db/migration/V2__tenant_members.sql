-- Multi-member tenants: replaces the single-owner model where
-- tenants.owner_email gated access. A tenant can now have any number of
-- members; each row in this table grants one user access to one tenant.
-- TenantContext checks membership instead of ownership; SUPER_ADMIN still
-- bypasses the check entirely.

CREATE TABLE tenant_members (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email       VARCHAR(200) NOT NULL,
    joined_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_tenant_member_tenant_email UNIQUE (tenant_id, email)
);

CREATE INDEX idx_tenant_member_email ON tenant_members(email);
CREATE INDEX idx_tenant_member_tenant ON tenant_members(tenant_id);

-- Backfill: every existing tenant.owner_email becomes a membership row so
-- existing owners don't lose access at deploy. Wrapped in a defensive check
-- because V1 didn't define owner_email — the column only exists in DBs that
-- were live-updated by Hibernate or hand-edited.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'tenants' AND column_name = 'owner_email'
    ) THEN
        EXECUTE $sql$
            INSERT INTO tenant_members (id, tenant_id, email, joined_at)
            SELECT gen_random_uuid(), id, owner_email, COALESCE(created_at, NOW())
            FROM tenants
            WHERE owner_email IS NOT NULL AND owner_email <> ''
            ON CONFLICT (tenant_id, email) DO NOTHING
        $sql$;
    END IF;
END $$;
