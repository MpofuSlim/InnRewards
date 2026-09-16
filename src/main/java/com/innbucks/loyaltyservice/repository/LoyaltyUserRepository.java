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

    /**
     * A bounded, RANDOM sample of distinct phones that would become spendable
     * if registered — PENDING projections plus sweeper age-outs
     * ({@code INACTIVE}/{@code PENDING_EXPIRED}, which {@code registerPhone}
     * recovers) whose phone has NEVER been registered. Feeds
     * {@code InnbucksValidateBacklogSweeper}.
     *
     * <p>The {@code NOT EXISTS} deliberately matches ANY registration row,
     * revoked ones included — unlike {@code findStaleUnregistered}'s live-only
     * filter. A revoked registration is an OPERATOR decision (a leaked key's
     * batch, or the eligibility decision itself reversed), and
     * {@code registerPhone} reinstates a revoked row on any fresh proof — so a
     * sweep that re-sampled revoked phones would re-validate and quietly undo
     * the revocation on its next pass, defeating the batch-revocation lever the
     * V44 migration documents. Housekeeping must not undo operators; a revoked
     * phone comes back only through a per-phone act (the app calling the
     * registration endpoint, or ticketing's OTP).
     *
     * <p>Random rather than oldest-first ON PURPOSE: a validate check can answer
     * "not a customer", and those phones stay in this result set until they age
     * out. Deterministic ordering would let a head-of-queue cluster of
     * non-customers absorb every batch forever while genuine customers behind
     * them never get checked; sampling makes coverage converge regardless.
     * Native SQL because JPQL has no random(); the DISTINCT runs in a subselect
     * because Postgres refuses {@code SELECT DISTINCT ... ORDER BY random()}.
     */
    @Query(nativeQuery = true, value = """
        SELECT p.phone_number FROM (
            SELECT DISTINCT u.phone_number
              FROM loyalty_users u
             WHERE (u.status = 'PENDING'
                    OR (u.status = 'INACTIVE' AND u.status_reason = 'PENDING_EXPIRED'))
               AND NOT EXISTS (SELECT 1 FROM phone_registrations r
                                WHERE r.phone_number = u.phone_number)
        ) p
        ORDER BY random()
        LIMIT :batch
        """)
    List<String> sampleUnregisteredBacklogPhones(@Param("batch") int batch);
}
