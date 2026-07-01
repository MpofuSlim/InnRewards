package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vouchers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_voucher_code", columnNames = "code")
}, indexes = {
        @Index(name = "idx_voucher_tenant", columnList = "tenant_id"),
        @Index(name = "idx_voucher_assignee", columnList = "assigned_user_id"),
        @Index(name = "idx_voucher_status", columnList = "status"),
        @Index(name = "idx_voucher_expires_at", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Voucher {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String signature;

    @Column(name = "assigned_user_id")
    private UUID assignedUserId;

    @Column(name = "assignee_phone", length = 32)
    private String assigneePhone;

    @Column(name = "assignee_name", length = 200)
    private String assigneeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ISSUED;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_channel", length = 20)
    private DeliveryChannel deliveryChannel;

    // Snapshot of the template's value at issuance time. Frozen here so a
    // merchant editing the template later (e.g. $5 → $10 discount) can't
    // retroactively change the worth of already-issued vouchers — like an
    // invoice line that captures the price at the moment of sale.
    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", length = 20)
    private VoucherTemplate.ValueType valueType;

    @Column(name = "face_value", precision = 19, scale = 4)
    private BigDecimal value;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "uses_remaining", nullable = false)
    private int usesRemaining = 1;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "viewed_at")
    private Instant viewedAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "campaign_source", length = 200)
    private String campaignSource;

    @Version
    private long version;

    public enum Status { ISSUED, DELIVERED, VIEWED, REDEEMED, PARTIALLY_USED, EXPIRED, REVOKED }
    public enum DeliveryChannel { SMS, WHATSAPP, EMAIL, PUSH, POS, NONE }
}
