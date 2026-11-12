-- V69: partner vouchers paid at the redeem ("Flow 2", provider-funded).
-- Owner's go, 2026-11-12.
--
-- A partner may generate a voucher WITHOUT a payment: the customer gets a code,
-- then pays it at one of the issuer's agents, whose redeem carries the payment.
-- The money goes from the agent's account straight to the issuer; nothing is
-- ever held for it. So there is no voucher until it is paid: the order keeps
-- the code (reserved_code), and the voucher is created and spent in one
-- transaction once the agent's payment is confirmed. An unpaid code is therefore
-- never in a voucher total, an expiry warning, a fee or the holding account.

ALTER TABLE voucher_purchase_orders
    ADD COLUMN IF NOT EXISTS pay_at_redeem BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS reserved_code VARCHAR(64);

ALTER TABLE voucher_purchase_orders ADD CONSTRAINT chk_vpo_pay_at_redeem
    CHECK (NOT pay_at_redeem OR (reserved_code IS NOT NULL AND partner_id IS NOT NULL));

-- A reserved code is unique among orders; VoucherService also keeps it apart
-- from every voucher code (it checks both when it allocates one).
CREATE UNIQUE INDEX IF NOT EXISTS uq_vpo_reserved_code
    ON voucher_purchase_orders (reserved_code) WHERE reserved_code IS NOT NULL;

-- One row per attempt to pay a code at an agent. The attempt is the money leg:
-- PENDING before the provider is called (a crash mid-call is resolved by asking
-- the provider, never by sending again), then CONFIRMED or FAILED. A FAILED
-- attempt moved nothing, so the code can be paid again under a new reference.
CREATE TABLE IF NOT EXISTS voucher_redeem_payments (
    id                  UUID           PRIMARY KEY,
    order_id            UUID           NOT NULL REFERENCES voucher_purchase_orders (id),
    partner_id          UUID           NOT NULL,
    merchant_id         UUID           NOT NULL,
    -- The redeem's own reference: the idempotency key, and the provider's.
    reference           VARCHAR(100)   NOT NULL,
    request_fingerprint VARCHAR(64)    NOT NULL,
    end_user_ref        VARCHAR(100),
    channel             VARCHAR(8)     NOT NULL,
    terminal_id         VARCHAR(64),
    funding_method      VARCHAR(24)    NOT NULL,
    -- The agent's account the payment is taken from.
    source_account      VARCHAR(34),
    card_approval_code  VARCHAR(32),
    card_last4          VARCHAR(4),
    collected_via       VARCHAR(24),
    collection_ref      VARCHAR(64),
    evidence_key        VARCHAR(160),
    amount              NUMERIC(19, 4) NOT NULL,
    currency            VARCHAR(8)     NOT NULL,
    status              VARCHAR(16)    NOT NULL,
    failure_code        VARCHAR(40),
    provider_ref        VARCHAR(100),
    -- The use recorded once the payment confirmed and the voucher was created.
    redemption_id       UUID,
    started_at          TIMESTAMPTZ    NOT NULL,
    finished_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT chk_vrp_status CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED')),
    CONSTRAINT chk_vrp_channel CHECK (channel IN ('POS', 'ONLINE', 'APP')),
    CONSTRAINT chk_vrp_amount CHECK (amount > 0)
);

-- The redeem reference is unique per merchant, for ever.
CREATE UNIQUE INDEX IF NOT EXISTS uq_vrp_reference
    ON voucher_redeem_payments (merchant_id, reference);
-- At most one live or successful payment per code: a second agent cannot start
-- one while the first is open, and a paid code cannot be paid again.
CREATE UNIQUE INDEX IF NOT EXISTS uq_vrp_live_per_order
    ON voucher_redeem_payments (order_id) WHERE status IN ('PENDING', 'CONFIRMED');
-- One piece of payment evidence pays one code (a refused attempt frees it).
CREATE UNIQUE INDEX IF NOT EXISTS uq_vrp_evidence
    ON voucher_redeem_payments (merchant_id, evidence_key)
    WHERE evidence_key IS NOT NULL AND status <> 'FAILED';
-- The reconciler's sweep: payments still open, or confirmed with the voucher
-- not yet recorded.
CREATE INDEX IF NOT EXISTS idx_vrp_open
    ON voucher_redeem_payments (started_at)
    WHERE status = 'PENDING' OR (status = 'CONFIRMED' AND redemption_id IS NULL);
