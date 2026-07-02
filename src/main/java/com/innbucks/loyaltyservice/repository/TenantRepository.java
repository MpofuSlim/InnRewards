package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    List<Tenant> findAllByCode(String code);

    /** Case-insensitive existence check — the guard against duplicate tenant names. */
    boolean existsByNameIgnoreCase(String name);

    List<Tenant> findAllByOwnerEmail(String ownerEmail);

    Page<Tenant> findAllByOwnerEmail(String ownerEmail, Pageable pageable);

    // Operator listings exclude the platform-internal ticketing container tenant
    // (seeded with a fixed id by V23) so it never shows up in the admin "Loyalty
    // Tenants" view. Excluded at the query level so pagination totals stay
    // accurate. See TicketingLoyaltyService.TICKETING_TENANT_ID.
    Page<Tenant> findByIdNot(UUID id, Pageable pageable);

    List<Tenant> findByIdNot(UUID id);

    /** Tenant count excluding the platform-internal ticketing container tenant,
     *  so the operator dashboard's TOTAL TENANTS matches the visible listing. */
    long countByIdNot(UUID id);
}
