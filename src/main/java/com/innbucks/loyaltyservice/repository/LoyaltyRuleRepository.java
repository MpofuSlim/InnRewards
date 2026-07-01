package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LoyaltyRuleRepository extends JpaRepository<LoyaltyRule, UUID> {

    @Query("""
        SELECT r FROM LoyaltyRule r
        WHERE r.tenantId = :tenantId
          AND r.transactionType = :type
          AND (r.merchantId = :merchantId OR r.merchantId IS NULL)
          AND r.active = true
        ORDER BY (CASE WHEN r.merchantId IS NULL THEN 1 ELSE 0 END), r.createdAt DESC
        """)
    List<LoyaltyRule> findApplicable(@Param("tenantId") UUID tenantId,
                                     @Param("merchantId") UUID merchantId,
                                     @Param("type") TransactionType type);

    List<LoyaltyRule> findByTenantId(UUID tenantId);

    Page<LoyaltyRule> findByTenantId(UUID tenantId, Pageable pageable);
}
