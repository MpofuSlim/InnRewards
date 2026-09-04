package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

// Loyalty-side projection of a user. Identity (email, fullName, nationalId)
// lives in user-service and must NOT be duplicated here. We keep only the
// stable foreign reference (phoneNumber) plus loyalty-specific state
// (per-tenant role, loyalty-program status, merchant attachment).
@Entity
@Table(name = "loyalty_users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_tenant_phone", columnNames = {"tenant_id", "phone_number"})
}, indexes = {
        @Index(name = "idx_user_tenant", columnList = "tenant_id")
})
@Getter
@Setter
@NoArgsConstructor
public class LoyaltyUser {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "merchant_id")
    private UUID merchantId;

    // Foreign reference to user-service; the customer's phone number is the
    // stable identifier across the platform.
    @Column(name = "phone_number", nullable = false, length = 32)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role = Role.END_USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    /**
     * Why this row is in a terminal status. NULL for ACTIVE/PENDING rows and for
     * every row written before V40.
     *
     * <p>Exists because {@code INACTIVE} conflates two different events with
     * opposite remedies: the expiry sweeper ageing out a phone nobody ever
     * proved ({@code PENDING_EXPIRED}, recoverable the moment a registration
     * arrives) and a human deliberately taking an account out of the programme
     * ({@code OPERATOR}, which a registration must NOT undo).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status_reason", length = 30)
    private StatusReason statusReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum Role { END_USER, MERCHANT_ADMIN, MERCHANT_FINANCE, TENANT_ADMIN, PLATFORM_ADMIN, AUDITOR }

    // Loyalty-program-specific status. BLOCKED here means "blocked from the
    // loyalty program" (e.g. by FraudService); it is independent of the
    // user's account status in user-service.
    //
    // PENDING is the "phone-keyed wallet" state: a sender (merchant, friend)
    // issued points/voucher to a phone whose owner has not yet proven they hold
    // it. Accrual works; spending is refused.
    //
    // SINCE V40 this column is a per-projection CACHE of a phone-level fact, not
    // the fact itself: `phone_registrations` is the source of truth for "the
    // owner of this number proved it". A projection is minted ACTIVE when the
    // phone is already registered, and a stale PENDING row heals at the spend
    // gate. Read UserService.isRegistrationPending, not this field, when the
    // question is "may this person spend".
    public enum Status { ACTIVE, BLOCKED, INACTIVE, PENDING }

    /** @see #getStatusReason() */
    public enum StatusReason {
        /** Aged out of PENDING by PendingUserExpirySweeper. Recoverable. */
        PENDING_EXPIRED,
        /** Deactivated deliberately by an operator. A registration must not revive this. */
        OPERATOR
    }
}
