package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Shop;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShopRepository extends JpaRepository<Shop, UUID> {
    List<Shop> findByTenantId(UUID tenantId);
    Page<Shop> findByTenantId(UUID tenantId, Pageable pageable);
    List<Shop> findByTenantIdAndMerchantId(UUID tenantId, UUID merchantId);
    Page<Shop> findByTenantIdAndMerchantId(UUID tenantId, UUID merchantId, Pageable pageable);

    // Duplicate-name guard for POST /loyalty/shops and the CSV bulk-upload. Shop
    // names are unique per merchant (case-insensitive) — two outlets under the same
    // merchant can't share a display name; different merchants may reuse one.
    // Enforced at the service level (409 SHOP_NAME_TAKEN / a failed bulk row)
    // rather than a DB unique index because existing rows may already hold
    // duplicates.
    boolean existsByMerchantIdAndNameIgnoreCase(UUID merchantId, String name);
}
