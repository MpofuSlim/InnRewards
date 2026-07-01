package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    @Query("""
        SELECT c FROM Campaign c
        WHERE c.tenantId = :tenantId
          AND (c.merchantId = :merchantId OR c.merchantId IS NULL)
          AND (c.transactionType = :type OR c.transactionType IS NULL)
          AND c.active = true
          AND c.startsAt <= :now AND c.endsAt >= :now
        ORDER BY c.multiplier DESC
        """)
    List<Campaign> findActive(@Param("tenantId") UUID tenantId,
                              @Param("merchantId") UUID merchantId,
                              @Param("type") TransactionType type,
                              @Param("now") Instant now);

    List<Campaign> findByTenantId(UUID tenantId);

    Page<Campaign> findByTenantId(UUID tenantId, Pageable pageable);

    // Duplicate-name guard for POST /loyalty/rules/campaigns. Campaign names are
    // unique per (tenant, merchant) case-insensitively. merchantId may be null
    // (tenant-wide campaign) — a derived IsNull query keeps that scope separate, so
    // a null-merchant name is only unique among other null-merchant campaigns for
    // the same tenant. Enforced at the service level (409 CAMPAIGN_NAME_TAKEN)
    // rather than a DB unique index because existing rows may already hold duplicates.
    boolean existsByTenantIdAndMerchantIdAndNameIgnoreCase(UUID tenantId, UUID merchantId, String name);
    boolean existsByTenantIdAndMerchantIdIsNullAndNameIgnoreCase(UUID tenantId, String name);
}
