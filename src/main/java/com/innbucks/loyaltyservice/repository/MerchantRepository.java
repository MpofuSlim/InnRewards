package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Merchant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
    List<Merchant> findByTenantId(UUID tenantId);
    Page<Merchant> findByTenantId(UUID tenantId, Pageable pageable);
    long countByTenantIdAndStatus(UUID tenantId, Merchant.Status status);

    // Powers /loyalty/merchants?unassigned=true — the service supplies the
    // ids of merchants that already have a MERCHANT_ADMIN (fetched from
    // user-service) and this page excludes them. The service must only call
    // this with a non-empty collection (Hibernate's `IN ()` is illegal SQL);
    // when the exclusion set is empty, fall through to findByTenantId.
    Page<Merchant> findByTenantIdAndIdNotIn(UUID tenantId, Collection<UUID> ids, Pageable pageable);

    // Used by user-service via the internal lookup endpoint to resolve a
    // MERCHANT_ADMIN's merchantId from their email at login. If a user happens
    // to admin more than one merchant, the earliest-created wins.
    Optional<Merchant> findFirstByAdminEmailOrderByCreatedAtAsc(String adminEmail);

    // Used by user-service to authorize a MERCHANT_ADMIN over their shops:
    // returns EVERY merchant the admin owns (case-insensitive email match), so
    // an admin running more than one merchant can still see/manage the staff of
    // all their shops — not just the earliest-created merchant.
    List<Merchant> findByAdminEmailIgnoreCase(String adminEmail);

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
