package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.FraudAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface FraudAttemptRepository extends JpaRepository<FraudAttempt, UUID> {
    List<FraudAttempt> findTop100ByOrderByCreatedAtDesc();
    long countByDeviceFingerprintAndCreatedAtAfter(String deviceFingerprint, Instant after);
    long countByCreatedAtAfter(Instant after);
    List<FraudAttempt> findTop100ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<FraudAttempt> findTop100ByTenantIdAndMerchantIdInOrderByCreatedAtDesc(
            UUID tenantId, java.util.Collection<UUID> merchantIds);
    /** Merchant-360 report: recent fraud pressure on one merchant. */
    long countByMerchantIdAndCreatedAtAfter(UUID merchantId, Instant after);

    /** {@link #countByMerchantIdAndCreatedAtAfter} for a page of merchants:
     *  {@code [merchantId, count]} (never called with an empty set). */
    @org.springframework.data.jpa.repository.Query("""
        SELECT f.merchantId, COUNT(f) FROM FraudAttempt f
        WHERE f.merchantId IN :merchantIds AND f.createdAt > :after
        GROUP BY f.merchantId
        """)
    List<Object[]> countByMerchantSince(
            @org.springframework.data.repository.query.Param("merchantIds") java.util.Collection<UUID> merchantIds,
            @org.springframework.data.repository.query.Param("after") Instant after);
}
