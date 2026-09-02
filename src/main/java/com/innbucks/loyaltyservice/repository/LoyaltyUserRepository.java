package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
    List<LoyaltyUser> findByStatusAndCreatedAtBefore(LoyaltyUser.Status status, Instant cutoff);

    /**
     * How many distinct phones hold a projection under more than one tenant.
     *
     * <p>This is exactly the population that {@code PublicTestController}'s points
     * writes refuse with {@code AMBIGUOUS_TENANT} while no tenant pin is
     * configured — {@code requireSingleProjection} throws precisely when
     * {@code findByPhoneNumber} returns more than one row and no pin narrows it.
     * {@link com.innbucks.loyaltyservice.config.PublicTestProvisioningCheck} logs
     * the count at boot so a half-provisioned cell announces how many customers
     * it is failing instead of discovering it one 400 at a time.
     *
     * <p>Native because it is a GROUP BY … HAVING over a derived set, which JPQL
     * cannot express as a scalar. Cheap: one aggregate over an already-indexed
     * column, run once at startup.
     */
    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT phone_number FROM loyalty_users
                GROUP BY phone_number
                HAVING COUNT(DISTINCT tenant_id) > 1
            ) multi_tenant_phones
            """, nativeQuery = true)
    long countPhonesSpanningTenants();
}
