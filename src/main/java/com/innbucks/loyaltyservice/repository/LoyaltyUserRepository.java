package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
