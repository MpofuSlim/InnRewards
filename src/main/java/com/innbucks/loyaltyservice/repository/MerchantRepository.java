package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Merchant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
    List<Merchant> findByTenantId(UUID tenantId);
    Page<Merchant> findByTenantId(UUID tenantId, Pageable pageable);
    long countByTenantIdAndStatus(UUID tenantId, Merchant.Status status);

    // Every loyalty merchant an organization owns — user-service's shop-staff
    // screens ask for this (ids-by-organization) to decide which merchants'
    // staff a merchant admin may manage. Oldest first, so the answer is stable.
    List<Merchant> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

    // Tenant membership by ORGANIZATION: a business whose organization owns a
    // merchant in a program works in that program, even one another business
    // created (TenantContext). Index-backed by idx_merchant_organization.
    boolean existsByTenantIdAndOrganizationId(UUID tenantId, UUID organizationId);

    // The ticketing bridge maps an event organizer (user_uuid) to one merchant.
    // Unique when set (uk_merchant_organizer), so at most one row matches.
    Optional<Merchant> findByOrganizerUuid(UUID organizerUuid);

    // Duplicate-name guard for POST /loyalty/merchants. Merchant names are unique
    // per tenant (case-insensitive), so a tenant can't onboard two merchants with
    // the same display name; different tenants may reuse a name. Enforced at the
    // service level (409 MERCHANT_NAME_TAKEN) rather than a DB unique index because
    // existing rows may already hold duplicates.
    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
}
