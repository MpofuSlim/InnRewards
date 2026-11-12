package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to pay a Flow 2 code at the issuer's agent (V69). The redeem
 * carries the payment: the agent's account pays the issuer directly, and once
 * that is CONFIRMED the voucher is created and spent in one go.
 *
 * <p>PENDING is written BEFORE the provider is called, so a crash mid-call is
 * resolved by asking the provider for the attempt's status, never by sending it
 * again. A FAILED attempt moved nothing; the code can be paid again under a new
 * reference. The unique indexes allow at most one PENDING or CONFIRMED attempt
 * per code.
 */
@Entity
@Table(name = "voucher_redeem_payments")
@Getter
@Setter
@NoArgsConstructor
public class VoucherRedeemPayment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    /** The merchant paid: always the code's issuer. */
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    /** The redeem's own reference: the idempotency key, unique per merchant. */
    @Column(nullable = false, length = 100)
    private String reference;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "end_user_ref", length = 100)
    private String endUserRef;

    @Column(nullable = false, length = 8)
    private String channel;

    @Column(name = "terminal_id", length = 64)
    private String terminalId;

    /** CASH, CARD_SWIPE or MERCHANT_COLLECTED: how the agent took the money. */
    @Column(name = "funding_method", nullable = false, length = 24)
    private String fundingMethod;

    /** The agent's account the payment is taken from. */
    @Column(name = "source_account", length = 34)
    private String sourceAccount;

    @Column(name = "card_approval_code", length = 32)
    private String cardApprovalCode;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "collected_via", length = 24)
    private String collectedVia;

    @Column(name = "collection_ref", length = 64)
    private String collectionRef;

    @Column(name = "evidence_key", length = 160)
    private String evidenceKey;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "failure_code", length = 40)
    private String failureCode;

    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    /** The use recorded once the payment confirmed and the voucher was created. */
    @Column(name = "redemption_id")
    private UUID redemptionId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    /** The CHECK {@code chk_vrp_status} lists every value. */
    public enum Status { PENDING, CONFIRMED, FAILED }
}
