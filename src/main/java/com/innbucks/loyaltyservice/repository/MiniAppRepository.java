package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.MiniApp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MiniAppRepository extends JpaRepository<MiniApp, UUID> {
    @Query("""
        SELECT m FROM MiniApp m
        WHERE m.enabled = true
          AND (m.tenantId = :tenantId OR m.tenantId IS NULL)
          AND (m.merchantId = :merchantId OR m.merchantId IS NULL)
        """)
    List<MiniApp> findEnabled(@Param("tenantId") UUID tenantId,
                              @Param("merchantId") UUID merchantId);
}
