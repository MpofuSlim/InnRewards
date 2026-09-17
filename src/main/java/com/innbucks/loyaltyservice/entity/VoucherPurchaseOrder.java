package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A voucher purchase that must be PAID before the voucher exists (V47).
 *
 * <p>Snapshot of one issue request (V46 sender identity and the creating
 * caller's JWT identity included) plus the money to collect. The voucher row
 * is created only at confirmation — by payment-service's S2S
 * {@code confirm-payment} for the electronic rails (EcoCash / InnBucks code /
 * card), or by a staff {@code confirm-cash} for cash in hand.
 *
 * <p>Expiry is LAZY: a row past {@link #expiresAt} while still
 * {@code PENDING_PAYMENT} is treated as expired by every reader (not payable,
 * not extendable, not cash-confirmable) without a sweeper flipping the column
 * — nothing is reserved by an order, so there is no held resource to release.
 * A late electronic confirmation is still honoured (see the service): by then
 * the money has moved, and refusing it would strand a paid customer.
 */
@Entity
@Table(name = "voucher_purchase_orders", uniqueConstraints = {
        @UniqueConstraint(name = "uk_vpo_order_ref", columnNames = "order_ref")
}, indexes = {
        @Index(name = "idx_vpo_tenant", columnList = "tenant_id"),
        @Index(name = "idx_vpo_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
public class VoucherPurchaseOrder {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_ref", nullable = false, length = 32)
    private String orderRef;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING_PAYMENT;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "payer_phone", nullable = false, length = 32)
    private String payerPhone;

    // ----- issue-request snapshot -----

    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_type", nullable = false, length = 20)
    private Voucher.VoucherType voucherType;

    @Column(name = "usage_limit", nullable = false)
    private int usageLimit;

    @Column(name = "assignee_phone", length = 32)
    private String assigneePhone;

    @Column(name = "assignee_name", length = 200)
    private String assigneeName;

    @Column(name = "assigned_user_id")
    private UUID assignedUserId;

    @Column(name = "sender_name", length = 200)
    private String senderName;

    @Column(name = "sender_phone", length = 32)
    private String senderPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_channel", length = 20)
    private Voucher.DeliveryChannel deliveryChannel;

    @Column(name = "campaign_source", length = 200)
    private String campaignSource;

    // Issuer identity snapshotted from the CREATING caller's JWT — the
    // confirmation is S2S with no caller context to stamp from.

    @Column(name = "issuer_user_id")
    private UUID issuerUserId;

    @Column(name = "issuer_phone", length = 32)
    private String issuerPhone;

    @Column(name = "issuer_email", length = 200)
    private String issuerEmail;

    @Column(name = "shop_id")
    private UUID shopId;

    // ----- outcome -----

    @Column(name = "voucher_id")
    private UUID voucherId;

    @Column(name = "payment_ref", length = 64)
    private String paymentRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "paid_via", length = 20)
    private PaidVia paidVia;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "cash_confirmed_by", length = 200)
    private String cashConfirmedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    public enum Status { PENDING_PAYMENT, PAID, EXPIRED, CANCELLED }

    /** GATEWAY = payment-service confirmed an electronic rail; CASH = a staff
     *  caller vouched for cash in hand. */
    public enum PaidVia { GATEWAY, CASH }

    /** Live-and-payable test used by every reader (lazy expiry). */
    public boolean payable(Instant now) {
        return status == Status.PENDING_PAYMENT && expiresAt != null && expiresAt.isAfter(now);
    }
}
