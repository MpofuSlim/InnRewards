-- Voucher purchase orders (V47): a voucher is PAID FOR before it exists.
--
-- THE PROBLEM. POST /loyalty/vouchers/issue mints the voucher the moment a
-- staff caller asks — there is no payment step. At the counter the customer
-- buying a gift voucher should first PAY (EcoCash PIN prompt, InnBucks 2D
-- code/QR, card, or cash in hand), and only a confirmed payment should issue
-- the voucher.
--
-- THE MODEL. This table is the loyalty-side ORDER for one voucher purchase:
-- a full snapshot of the issue request (everything POST /issue takes, V46
-- sender identity included), the money to collect (the voucher's face value
-- in its currency), and the payer's phone — captured while the staff caller's
-- JWT is present, because the eventual payment confirmation arrives S2S from
-- payment-service with no user context at all.
--
--   PENDING_PAYMENT --(payment-service confirm-payment / staff confirm-cash)--> PAID (+ voucher issued)
--   PENDING_PAYMENT --(expires_at passes unpaid)-------------------------------> treated as EXPIRED (lazy)
--   PENDING_PAYMENT --(staff cancel)-------------------------------------------> CANCELLED
--
-- The electronic rails live in ticketing-system's payment-service, which
-- already collects money for ANY product behind an OrderGateway (bookings,
-- marketplace orders). This table is what its LOYALTY_VOUCHER gateway reads
-- (GET /loyalty/internal/voucher-orders/{ref}), extends
-- (.../extend-expiry) and confirms (.../confirm-payment). Cash never touches
-- payment-service: a staff caller confirms it here (confirm-cash) and their
-- identity is recorded — that confirmation IS the payment proof.
--
-- The voucher row itself is created ONLY at confirmation, by the same code
-- path as a direct issue — fees, FX freeze, expiry-from-rules, recipient
-- WhatsApp and the sender's confirmation copy all ride along unchanged.

CREATE TABLE voucher_purchase_orders (
    id                UUID          PRIMARY KEY,

    -- The cross-service handle payment-service keys on (VCH-<12 hex>), the
    -- shape of a marketplace MKT- ref. Opaque, unguessable enough to not be
    -- an enumeration surface (the internal surface is edge-denied anyway).
    order_ref         VARCHAR(32)   NOT NULL UNIQUE,

    tenant_id         UUID          NOT NULL,
    merchant_id       UUID          NOT NULL,

    status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING_PAYMENT',

    -- What the payer owes: the voucher's face value, verbatim. NUMERIC(19,4)
    -- matches vouchers.face_value.
    amount            NUMERIC(19,4) NOT NULL,
    currency          VARCHAR(8)    NOT NULL,

    -- The phone the payment instrument targets: EcoCash pushes its PIN prompt
    -- here; the InnBucks code narration names it. Defaults from the sender
    -- (the person gifting is usually the person paying), else the assignee.
    payer_phone       VARCHAR(32)   NOT NULL,

    -- ----- issue-request snapshot (mirrors the vouchers columns) -----
    voucher_type      VARCHAR(20)   NOT NULL,
    usage_limit       INTEGER       NOT NULL,
    assignee_phone    VARCHAR(32),
    assignee_name     VARCHAR(200),
    assigned_user_id  UUID,
    sender_name       VARCHAR(200),
    sender_phone      VARCHAR(32),
    delivery_channel  VARCHAR(20),
    campaign_source   VARCHAR(200),

    -- Issuer identity, snapshotted from the CREATING caller's JWT — the
    -- confirmation is S2S and has no caller to stamp from.
    issuer_user_id    UUID,
    issuer_phone      VARCHAR(32),
    issuer_email      VARCHAR(200),
    shop_id           UUID,

    -- ----- outcome -----
    voucher_id        UUID REFERENCES vouchers(id),
    -- payment-service's stable payment reference (its idempotency handle on
    -- confirm-payment), or CASH-<uuid> for a cash confirmation.
    payment_ref       VARCHAR(64),
    -- GATEWAY (payment-service confirmed an electronic rail) or CASH.
    paid_via          VARCHAR(20),
    paid_at           TIMESTAMPTZ,
    -- The staff account (JWT subject) that confirmed a cash payment — the
    -- audit answer to "who took the money".
    cash_confirmed_by VARCHAR(200),

    expires_at        TIMESTAMPTZ   NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT chk_vpo_status
        CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT chk_vpo_paid_via
        CHECK (paid_via IS NULL OR paid_via IN ('GATEWAY', 'CASH'))
);

CREATE INDEX idx_vpo_tenant  ON voucher_purchase_orders (tenant_id);
CREATE INDEX idx_vpo_status  ON voucher_purchase_orders (status);
