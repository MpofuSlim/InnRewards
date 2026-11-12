package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** One merchant a side has put into a partnership (V68). */
@Entity
@Table(name = "voucher_partnership_merchants")
@IdClass(VoucherPartnershipMerchant.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class VoucherPartnershipMerchant {

    @Id
    @Column(name = "partnership_id", nullable = false)
    private UUID partnershipId;

    @Id
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "added_by", nullable = false, length = 254)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @lombok.AllArgsConstructor
    @lombok.EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID partnershipId;
        private UUID merchantId;
    }
}
