package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "invoices", indexes = {
        @Index(name = "idx_invoice_merchant", columnList = "merchant_id"),
        @Index(name = "idx_invoice_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
public class Invoice {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "invoice_number", nullable = false, length = 40, unique = true)
    private String invoiceNumber;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "points_issued", nullable = false, precision = 19, scale = 4)
    private BigDecimal pointsIssued = BigDecimal.ZERO;

    @Column(name = "points_redeemed", nullable = false, precision = 19, scale = 4)
    private BigDecimal pointsRedeemed = BigDecimal.ZERO;

    @Column(name = "vouchers_issued", nullable = false)
    private long vouchersIssued = 0;

    @Column(name = "vouchers_redeemed", nullable = false)
    private long vouchersRedeemed = 0;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(length = 8)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum Status { PENDING, PAID, OVERDUE, CANCELLED }
}
