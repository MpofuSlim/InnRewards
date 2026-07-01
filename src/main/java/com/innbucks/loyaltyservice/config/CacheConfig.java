package com.innbucks.loyaltyservice.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Caching for hot-path lookups. Tenants and tenant memberships barely change
 * at runtime but are read on every authenticated request via TenantContext, so
 * a short-TTL in-memory cache shaves two DB queries off the critical path.
 *
 * <p>Caches are local to each replica (Caffeine, not Redis). The TTL is short
 * enough that staleness is bounded; mutations (tenant suspend/activate,
 * member join/leave) call {@code @CacheEvict} on the specific key so changes
 * propagate immediately on the replica that received them, and within at
 * most {@code TTL} seconds on the rest. For multi-replica deployments where
 * stricter consistency matters, swap CaffeineCacheManager for a distributed
 * one (Redis) without touching call sites — the @Cacheable annotations
 * stay identical.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 5 minutes covers most "I just joined a tenant and want to see it" UX. */
    private static final int TENANT_TTL_MINUTES = 5;
    /** Bigger caps prevent unbounded growth if traffic is unusually spiky. */
    private static final int TENANT_MAX_SIZE = 1_000;
    private static final int MEMBERSHIP_MAX_SIZE = 10_000;

    public static final String CACHE_TENANTS = "tenants";
    public static final String CACHE_TENANT_MEMBERSHIP = "tenantMembership";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheNames(List.of(CACHE_TENANTS, CACHE_TENANT_MEMBERSHIP));
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(TENANT_TTL_MINUTES, TimeUnit.MINUTES)
                .maximumSize(MEMBERSHIP_MAX_SIZE)
                .recordStats());
        return manager;
    }
}
