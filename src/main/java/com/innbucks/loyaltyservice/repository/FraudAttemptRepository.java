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
}
