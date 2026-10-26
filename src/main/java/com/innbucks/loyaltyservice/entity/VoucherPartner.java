package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A calling system that generates and redeems vouchers through the partner API
 * (V62): the InnBucks middleware. Every request is signed by one of its keys
 * ({@link VoucherPartnerKey}).
 *
 * <p>A partner is NOT a merchant. It serves any number of merchants, each linked
 * through a {@link VoucherPartnerMerchant} and named in every request by its
 * code; a request for a merchant it is not linked to is refused. MWOS is the
 * first merchant; the next one is a setup step, not a code change.
 */
@Entity
@Table(name = "voucher_partners")
@Getter
@Setter
@NoArgsConstructor
public class VoucherPartner {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    /** The JWS {@code iss} this partner's requests must carry; unique. */
    @Column(nullable = false, length = 100)
    private String issuer;

    /** Comma-separated IP literals or CIDR ranges, matched against X-Real-IP.
     *  Empty refuses every call. */
    @Column(name = "allowed_ips", nullable = false, length = 1000)
    private String allowedIps = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** The CHECK {@code chk_voucher_partners_status} lists every value. */
    public enum Status { ACTIVE, SUSPENDED }
}
