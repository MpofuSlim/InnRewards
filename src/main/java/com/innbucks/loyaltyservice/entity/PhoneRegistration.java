package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The fact that the owner of a phone number has PROVEN they own it — recorded
 * once, per phone, for the whole platform (V40).
 *
 * <p>This is the thing {@link LoyaltyUser.Status#PENDING} was always trying to
 * express and could not: a {@code LoyaltyUser} is a per-TENANT projection, so
 * storing "is registered" on it means the answer is re-asked, and re-answered
 * wrongly, every time the customer touches a new merchant. Registration is a
 * property of the human holding the SIM, not of a tenant's view of them.
 *
 * <p>{@code loyalty_users.status} remains as the per-projection cache of this
 * fact — minted ACTIVE when the phone is already registered, healed at the
 * spend gate, never aged out by the sweeper while a registration stands.
 *
 * <p>The primary key is the E.164 phone itself, in the exact spelling
 * {@code MsisdnValidator.normalizeToE164} writes into
 * {@code loyalty_users.phone_number} and {@code wallets.phone_number}. Every
 * writer goes through {@code UserService.normalizePhone} first, so there is one
 * spelling and no drift.
 */
@Entity
@Table(name = "phone_registrations")
@Getter
@Setter
@NoArgsConstructor
public class PhoneRegistration {

    @Id
    @Column(name = "phone_number", nullable = false, length = 32)
    private String phoneNumber;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Source source;

    @Column(name = "source_ref", length = 120)
    private String sourceRef;

    @Column(name = "last_asserted_at")
    private Instant lastAssertedAt;

    @Column(name = "last_assertion_jti", length = 64)
    private String lastAssertionJti;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 200)
    private String revokedReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * Which proof registered this phone. Kept because the sources do not carry
     * equal weight and an incident is scoped by source: revoking everything a
     * leaked partner key minted must not also revoke what ticketing's OTP flow
     * proved.
     */
    public enum Source {
        /** ticketing user-service's signup webhook — the original path. */
        TICKETING_OTP,
        /** A signed, phone-scoped assertion from the app's backend. */
        PARTNER_ASSERTION,
        /** The same endpoint in shared-key mode, for a caller that cannot sign. */
        PARTNER_KEY,
        /** The customer's own Veengu session, validated server-side against
         *  Veengu's GET /auth/identity — the phone comes from Veengu's answer,
         *  never from the caller (V41). */
        VEENGU_SESSION
    }

    /** Live = not revoked. A revoked row is history, never a grant. */
    public boolean isLive() {
        return revokedAt == null;
    }
}
