-- V64: a tenant code is a short unique name, not the country.
--
-- The code is what a caller may send as X-Tenant-Code instead of the tenant
-- id. The portal filled it with the country ("ZW"), so most tenants shared one
-- code and every lookup by it answered AMBIGUOUS_TENANT: a shared code already
-- identified nobody. Nothing in the fleet sends X-Tenant-Code (integrations
-- send X-Tenant-Id), so renaming a shared code changes no working behaviour.
--
-- 1. Every tenant whose code another tenant shares (letter case ignored) gets a
--    code made from its name: lowercase letters and digits, every other run of
--    characters one hyphen, at most 46 characters ("Simbisa Brands" ->
--    "simbisa-brands"). A name that yields nothing becomes "tenant". If that
--    code is taken, -2, -3 ... is added. Tenants are taken oldest first.
--    A code no other tenant shares is left exactly as it is.
-- 2. A unique index on lower(code) then stops two tenants ever sharing one
--    again. TenantService checks first, so a caller gets 409 TENANT_CODE_TAKEN;
--    the index catches two creates racing.
DO $$
DECLARE
    rec       RECORD;
    base      TEXT;
    candidate TEXT;
    n         INT;
BEGIN
    FOR rec IN
        SELECT t.id, t.name
        FROM tenants t
        WHERE lower(t.code) IN (SELECT lower(code) FROM tenants GROUP BY lower(code) HAVING count(*) > 1)
        ORDER BY t.created_at, t.id
    LOOP
        base := left(trim(both '-' from regexp_replace(lower(rec.name), '[^a-z0-9]+', '-', 'g')), 46);
        base := trim(both '-' from base);
        IF base IS NULL OR length(base) < 2 THEN
            base := 'tenant';
        END IF;
        candidate := base;
        n := 1;
        WHILE EXISTS (SELECT 1 FROM tenants WHERE lower(code) = candidate AND id <> rec.id) LOOP
            n := n + 1;
            candidate := base || '-' || n;
        END LOOP;
        UPDATE tenants SET code = candidate WHERE id = rec.id;
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenants_code_lower ON tenants (lower(code));
