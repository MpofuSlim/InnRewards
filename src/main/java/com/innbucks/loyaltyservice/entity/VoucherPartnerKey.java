package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A partner's PUBLIC signing key (V62). The private key never leaves the
 * partner. Several keys may be ACTIVE at once, so a rotation causes no
 * downtime: register the new key, let the partner switch, revoke the old one.
 */
@Entity
@Table(name = "voucher_partner_keys")
@Getter
@Setter
@NoArgsConstructor
public class VoucherPartnerKey {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    /** The JWS {@code kid}; unique across every partner. */
    @Column(nullable = false, length = 64)
    private String kid;

    /** The one algorithm this key may sign with. Anything else is refused. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Alg alg;

    @Column(name = "public_key_pem", nullable = false, columnDefinition = "TEXT")
    private String publicKeyPem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** The CHECK {@code chk_voucher_partner_keys_alg} lists every value. */
    public enum Alg { ES256, RS256 }

    /** The CHECK {@code chk_voucher_partner_keys_status} lists every value. */
    public enum Status { ACTIVE, REVOKED }
}
