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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum Role { END_USER, MERCHANT_ADMIN, MERCHANT_FINANCE, TENANT_ADMIN, PLATFORM_ADMIN, AUDITOR }

    // Loyalty-program-specific status. BLOCKED here means "blocked from the
    // loyalty program" (e.g. by FraudService); it is independent of the
    // user's account status in user-service.
    //
    // PENDING is the "phone-keyed wallet" state: a sender (merchant, friend)
    // issued points/voucher to a phone that has not yet registered in
    // user-service. Accrual works; redemption is blocked until user-service
    // confirms the phone has signed up — at which point the row is flipped to
    // ACTIVE by POST /loyalty/internal/users/promote.
    public enum Status { ACTIVE, BLOCKED, INACTIVE, PENDING }
}
