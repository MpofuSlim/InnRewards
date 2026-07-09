package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * A physical outlet under a Merchant. e.g. "Pizza Inn Avondale" is a shop
 * under the "Pizza Inn" merchant. Shops inherit the merchant's rules; if the
 * merchant has none, they inherit the global tenant-wide rules via RulesEngine.
 */
@Entity
@Table(name = "shops", indexes = {
        @Index(name = "idx_shop_tenant", columnList = "tenant_id"),
        @Index(name = "idx_shop_merchant", columnList = "merchant_id"),
        @Index(name = "idx_shop_tenant_merchant", columnList = "tenant_id,merchant_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Shop extends Auditable {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 300)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    public enum Status { ACTIVE, INACTIVE }
}
