-- Full audit trail (created/updated when + by whom) on the admin-configurable
-- entities, auto-populated by Spring Data JPA auditing (the Auditable
-- @MappedSuperclass + JpaAuditingConfig). created_at already exists on all six;
-- this adds updated_at plus the actor columns.
--
-- Nullable: legacy rows and any system / unauthenticated write leave them null
-- (no historical actor exists to backfill). updated_at is TIMESTAMP WITH TIME
-- ZONE to match created_at (Hibernate maps the entity's Instant fields to
-- timestamptz; ddl-auto=validate requires an exact match). The actor columns
-- hold a user_uuid string or a JWT email, hence VARCHAR, not UUID.

ALTER TABLE merchants
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE shops
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE loyalty_rules
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE campaigns
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE voucher_templates
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);
