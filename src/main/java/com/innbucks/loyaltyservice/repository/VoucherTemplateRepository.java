package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VoucherTemplateRepository extends JpaRepository<VoucherTemplate, UUID> {
    List<VoucherTemplate> findByTenantId(UUID tenantId);
    Page<VoucherTemplate> findByTenantId(UUID tenantId, Pageable pageable);
    List<VoucherTemplate> findByTenantIdAndMerchantId(UUID tenantId, UUID merchantId);

    // Duplicate-name guard for POST /loyalty/vouchers/templates. Template names are
    // unique per (tenant, merchant) case-insensitively. merchantId may be null
    // (tenant-wide template) — a derived IsNull query keeps that scope separate, so
    // a null-merchant name is only unique among other null-merchant templates for
    // the same tenant. Enforced at the service level (409 VOUCHER_TEMPLATE_NAME_TAKEN)
    // rather than a DB unique index because existing rows may already hold duplicates
    // (and a nullable column doesn't unique-constrain cleanly across DBs anyway).
    boolean existsByTenantIdAndMerchantIdAndNameIgnoreCase(UUID tenantId, UUID merchantId, String name);
    boolean existsByTenantIdAndMerchantIdIsNullAndNameIgnoreCase(UUID tenantId, String name);
}
