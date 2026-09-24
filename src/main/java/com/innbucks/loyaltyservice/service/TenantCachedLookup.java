package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.CacheConfig;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantMemberRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Cacheable thin wrapper over the tenant + membership repositories. Lives
 * outside {@link TenantService} so the @Transactional class-level setting on
 * the service doesn't muddy the AOP order around @Cacheable; this component
 * is intentionally NOT @Transactional so a cache hit short-circuits before
 * any JPA bookkeeping.
 *
 * <p>Called from {@link com.innbucks.loyaltyservice.security.TenantContext} on
 * every authenticated request — exactly the hot path the cache exists to serve.
 */
@Component
public class TenantCachedLookup {

    private final TenantRepository tenants;
    private final TenantMemberRepository members;
    private final MerchantRepository merchants;

    public TenantCachedLookup(TenantRepository tenants, TenantMemberRepository members,
                              MerchantRepository merchants) {
        this.tenants = tenants;
        this.members = members;
        this.merchants = merchants;
    }

    @Cacheable(value = CacheConfig.CACHE_TENANTS, key = "#id")
    public Optional<Tenant> findById(UUID id) {
        return tenants.findById(id);
    }

    @Cacheable(value = CacheConfig.CACHE_TENANT_MEMBERSHIP,
            key = "#tenantId + ':' + #email")
    public boolean isMember(UUID tenantId, String email) {
        return members.existsByTenantIdAndEmail(tenantId, email);
    }

    /**
     * Membership check by the caller's stable UUID. Cached in the same
     * Caffeine cache as {@link #isMember(UUID, String)} but under a
     * {@code ':u:'}-namespaced key so a UUID key can never collide with an
     * email key.
     */
    @Cacheable(value = CacheConfig.CACHE_TENANT_MEMBERSHIP,
            key = "#tenantId + ':u:' + #userId")
    public boolean isMemberByUserId(UUID tenantId, UUID userId) {
        return members.existsByTenantIdAndUserId(tenantId, userId);
    }

    /**
     * Whether an organization owns at least one merchant in this program — the
     * second way a business is a member of a tenant it did not create (the
     * first being {@code tenant.organizationId}). Deliberately NOT cached: it
     * turns false the moment a merchant moves to another business, and a stale
     * true would keep a former owner inside the program. It is one indexed
     * existence check, and only reached by a caller no cheaper rule admitted.
     */
    public boolean organizationOwnsMerchantIn(UUID tenantId, UUID organizationId) {
        return merchants.existsByTenantIdAndOrganizationId(tenantId, organizationId);
    }
}
