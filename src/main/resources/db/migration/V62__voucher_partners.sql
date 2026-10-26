-- V62 — vouchers generated and redeemed by a PARTNER through the InnBucks
-- middleware (first partner: MWOS, a betting company).
--
-- The merchant does not log in. The middleware calls three endpoints
-- (/loyalty/partner/vouchers/{generate,inquire,redeem}) with every request
-- signed by its own private key. A partner voucher's value is held in an
-- InnBucks holding account until it is used ("hold until redeemed", owner
-- decision 2026-10-23): the voucher exists only once the money has moved into
-- holding, and each redemption pays MWOS the amount used.
--
-- This migration is the data half. Money is moved through a funding provider
-- behind a port; on staging that provider is SIMULATED (every leg confirms),
-- and the real one (BR or Veengu, through payment-service) arrives later
-- without a schema change here.
--
-- Additive throughout: two new tables, nullable columns, defaults and partial
-- indexes. The previous build ignores all of it on a rollback.

-- ---------------------------------------------------------------------------
-- 1. Partners: a CALLING SYSTEM (the InnBucks middleware), not a merchant. One
--    partner serves any number of merchants (step 3): MWOS is the first, and
--    adding the next one is a setup step, not a code change.
CREATE TABLE IF NOT EXISTS voucher_partners (
    id                UUID PRIMARY KEY,
    name              VARCHAR(100) NOT NULL,
    -- The JWS `iss` the partner's requests must carry.
    issuer            VARCHAR(100) NOT NULL,
    -- Comma-separated IP literals or CIDR ranges the requests may come from
    -- (read from X-Real-IP, never X-Forwarded-For). Empty refuses every call.
    allowed_ips       VARCHAR(1000) NOT NULL DEFAULT '',
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_voucher_partners_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_voucher_partners_issuer ON voucher_partners (issuer);

-- 2. The partner's PUBLIC keys. The private keys never leave the middleware.
--    Several keys may be ACTIVE at once, which is how a rotation causes no
--    downtime: register the new key, let the partner switch, revoke the old.
CREATE TABLE IF NOT EXISTS voucher_partner_keys (
    id              UUID PRIMARY KEY,
    partner_id      UUID         NOT NULL REFERENCES voucher_partners(id),
    kid             VARCHAR(64)  NOT NULL,
    alg             VARCHAR(8)   NOT NULL,
    public_key_pem  TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_at      TIMESTAMPTZ,
    CONSTRAINT chk_voucher_partner_keys_alg CHECK (alg IN ('ES256', 'RS256')),
    CONSTRAINT chk_voucher_partner_keys_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_voucher_partner_keys_kid ON voucher_partner_keys (kid);

-- 3. Which merchants a partner may act for, each named by a short code the
--    partner sends in every request (e.g. "MWOS"). A request for a merchant
--    the partner is not linked to is refused. Per-merchant settings live here
--    because merchants differ: a betting shop and a restaurant need different
--    limits and take different payment methods. NULL means "the cell default".
CREATE TABLE IF NOT EXISTS voucher_partner_merchants (
    id                     UUID PRIMARY KEY,
    partner_id             UUID          NOT NULL REFERENCES voucher_partners(id),
    merchant_id            UUID          NOT NULL REFERENCES merchants(id),
    tenant_id              UUID          NOT NULL,
    merchant_code          VARCHAR(32)   NOT NULL,
    -- When true, a POS generate without a terminalId is refused. Off until the
    -- middleware confirms it sends outlets and terminals (open question).
    require_terminal       BOOLEAN       NOT NULL DEFAULT FALSE,
    -- When true, a redeem must carry the verified phone of the customer's
    -- account on the partner's platform, and it must be the voucher's holder
    -- phone, so a leaked code is useless to anyone else.
    bind_redeem_to_holder  BOOLEAN       NOT NULL DEFAULT TRUE,
    -- Comma-separated funding methods this merchant accepts; NULL = all the
    -- cell supports.
    funding_methods        VARCHAR(200),
    max_value              NUMERIC(19,4),
    per_phone_daily_count  INTEGER,
    per_phone_daily_value  NUMERIC(19,4),
    status                 VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_voucher_partner_merchants_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_voucher_partner_merchants_code
    ON voucher_partner_merchants (partner_id, merchant_code);
CREATE UNIQUE INDEX IF NOT EXISTS uq_voucher_partner_merchants_merchant
    ON voucher_partner_merchants (partner_id, merchant_id);

-- ---------------------------------------------------------------------------
-- 4. merchants.escrow_mode — a merchant whose vouchers exist only through its
--    partner. Its vouchers are refused everywhere else: no free issue, no
--    till purchase order, no till / app / public redemption, no transfer.
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS escrow_mode BOOLEAN NOT NULL DEFAULT FALSE;

-- 5. vouchers.partner_id — set on every voucher a partner generated. It is
--    what the non-partner surfaces check to refuse one.
ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS partner_id UUID;
CREATE INDEX IF NOT EXISTS idx_vouchers_partner ON vouchers (partner_id) WHERE partner_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 6. A partner's generate is a voucher purchase order (V47) with the partner's
--    own reference and the funding leg's state.
ALTER TABLE voucher_purchase_orders
    ADD COLUMN IF NOT EXISTS partner_id            UUID,
    ADD COLUMN IF NOT EXISTS partner_reference     VARCHAR(64),
    ADD COLUMN IF NOT EXISTS request_fingerprint   VARCHAR(64),
    ADD COLUMN IF NOT EXISTS channel               VARCHAR(8),
    ADD COLUMN IF NOT EXISTS outlet                VARCHAR(64),
    ADD COLUMN IF NOT EXISTS terminal_id           VARCHAR(64),
    ADD COLUMN IF NOT EXISTS operator_ref          VARCHAR(64),
    ADD COLUMN IF NOT EXISTS end_user_ref          VARCHAR(100),
    ADD COLUMN IF NOT EXISTS funding_method        VARCHAR(24),
    -- NOT_STARTED -> AWAITING (the customer must still pay) / PENDING (sent,
    -- outcome unknown) -> CONFIRMED / FAILED. FAILED is final for the order.
    ADD COLUMN IF NOT EXISTS funding_status        VARCHAR(16),
    ADD COLUMN IF NOT EXISTS funding_failure_code  VARCHAR(40),
    -- The provider's reference for the leg into holding.
    ADD COLUMN IF NOT EXISTS funding_ref           VARCHAR(100),
    -- The payment evidence that may fund one order only: a card approval code
    -- (scoped per terminal per day) or MWOS's own collection reference.
    ADD COLUMN IF NOT EXISTS funding_evidence_key  VARCHAR(160),
    ADD COLUMN IF NOT EXISTS payment_code          VARCHAR(32),
    ADD COLUMN IF NOT EXISTS payment_expires_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS funding_started_at    TIMESTAMPTZ;

ALTER TABLE voucher_purchase_orders ADD CONSTRAINT chk_vpo_funding_status
    CHECK (funding_status IS NULL OR funding_status IN ('NOT_STARTED', 'AWAITING', 'PENDING', 'CONFIRMED', 'FAILED'));
ALTER TABLE voucher_purchase_orders ADD CONSTRAINT chk_vpo_channel
    CHECK (channel IS NULL OR channel IN ('POS', 'ONLINE'));

-- The partner's reference is the idempotency key: unique for the merchant,
-- for ever. Claimed BEFORE any money moves.
CREATE UNIQUE INDEX IF NOT EXISTS uq_vpo_partner_reference
    ON voucher_purchase_orders (merchant_id, partner_reference) WHERE partner_reference IS NOT NULL;
-- One piece of payment evidence funds one order.
CREATE UNIQUE INDEX IF NOT EXISTS uq_vpo_funding_evidence
    ON voucher_purchase_orders (merchant_id, funding_evidence_key) WHERE funding_evidence_key IS NOT NULL;
-- The reconciler's sweep: partner orders whose funding is still open.
CREATE INDEX IF NOT EXISTS idx_vpo_partner_open_funding
    ON voucher_purchase_orders (funding_status, created_at)
    WHERE partner_id IS NOT NULL AND funding_status IN ('AWAITING', 'PENDING', 'CONFIRMED');
-- Duplicate-sale check and per-phone daily limits.
CREATE INDEX IF NOT EXISTS idx_vpo_partner_phone_created
    ON voucher_purchase_orders (partner_id, assignee_phone, created_at) WHERE partner_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 7. A partner redemption records who redeemed it on the partner's platform
--    and whether the amount has been paid to the merchant from holding.
ALTER TABLE voucher_redemptions
    ADD COLUMN IF NOT EXISTS partner_id         UUID,
    ADD COLUMN IF NOT EXISTS end_user_ref       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS channel            VARCHAR(8),
    ADD COLUMN IF NOT EXISTS terminal_id        VARCHAR(64),
    -- PENDING -> SETTLED, or ATTENTION when an operator must resolve it.
    ADD COLUMN IF NOT EXISTS settlement_status  VARCHAR(16),
    ADD COLUMN IF NOT EXISTS settlement_ref     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS settled_at         TIMESTAMPTZ;

ALTER TABLE voucher_redemptions ADD CONSTRAINT chk_voucher_redemptions_settlement
    CHECK (settlement_status IS NULL OR settlement_status IN ('PENDING', 'SETTLED', 'ATTENTION'));
CREATE INDEX IF NOT EXISTS idx_voucher_redemptions_settlement_pending
    ON voucher_redemptions (redeemed_at) WHERE settlement_status = 'PENDING';
