package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoyaltyUserRepository extends JpaRepository<LoyaltyUser, UUID> {
    Optional<LoyaltyUser> findByTenantIdAndPhoneNumber(UUID tenantId, String phoneNumber);
    List<LoyaltyUser> findByTenantId(UUID tenantId);

    // Cross-tenant lookup used by the promote-on-registration webhook: a phone
    // may have pending balances under multiple tenants, all of which flip to
    // ACTIVE together when user-service confirms the signup.
    List<LoyaltyUser> findByPhoneNumber(String phoneNumber);

    // PENDING accounts older than the TTL get aged out by the expiry sweeper.
    // Kept for compatibility; the sweeper now uses findStaleUnregistered so a
    // registered phone is never aged out.
    List<LoyaltyUser> findByStatusAndCreatedAtBefore(LoyaltyUser.Status status, Instant cutoff);

    /**
     * PENDING projections whose phone IS registered — i.e. rows whose cached
     * status has fallen behind the phone-level fact (V40).
     *
     * <p>They exist because registration and projection-creation race in both
     * directions: a projection minted between the proof arriving and this
     * sweep, or a row that was already PENDING when an older proof landed. The
     * spend gate heals whichever the customer touches; this converges the rest
     * so a report or an admin screen never shows a registered customer as
     * pending.
     */
    @Query("""
        SELECT u FROM LoyaltyUser u
         WHERE u.status = com.innbucks.loyaltyservice.entity.LoyaltyUser.Status.PENDING
           AND EXISTS (SELECT 1 FROM PhoneRegistration r
                        WHERE r.phoneNumber = u.phoneNumber
                          AND r.revokedAt IS NULL)
        """)
    List<LoyaltyUser> findPendingButRegistered();

    /**
     * PENDING projections older than the cutoff whose phone is NOT registered —
     * the only rows the expiry sweeper may age out.
     *
     * <p>The {@code NOT EXISTS} is the load-bearing half. Ageing out a
     * registered phone would push it to INACTIVE, which the spend gate refuses
     * and which {@code registerPhone} only recovers via the
     * {@code PENDING_EXPIRED} reason — a round trip through two bugs to arrive
     * back where it started. A phone whose owner has proven it simply never
     * expires.
     */
    @Query("""
        SELECT u FROM LoyaltyUser u
         WHERE u.status = com.innbucks.loyaltyservice.entity.LoyaltyUser.Status.PENDING
           AND u.createdAt < :cutoff
           AND NOT EXISTS (SELECT 1 FROM PhoneRegistration r
                            WHERE r.phoneNumber = u.phoneNumber
                              AND r.revokedAt IS NULL)
        """)
    List<LoyaltyUser> findStaleUnregistered(@Param("cutoff") Instant cutoff);
}
