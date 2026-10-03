package com.innbucks.loyaltyservice.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cached "is this phone a staff member of this merchant?" lookup backing the
 * earn-integrity {@code STAFF_RECIPIENT} guard. The authoritative set comes
 * from user-service's internal shop-staff endpoint; a Caffeine cache
 * (TTL {@code loyalty.earn.staff-cache-seconds}, default 300s) keeps the
 * money path off the network for all but one call per merchant per window.
 *
 * <p><b>Fails open, deliberately.</b> The guard runs inside the earn path, and
 * a user-service outage must degrade to "guard off" — never to blocked tills
 * or added per-earn timeouts. An unreachable/failed lookup is cached as a
 * NON-authoritative empty set for the same TTL (so an outage costs one failed
 * call per merchant per window, not one per earn) and every such load is
 * logged loudly. The distinction between "no staff" (authoritative empty,
 * normal) and "unknown" (failure) is kept in the cache entry so the log tells
 * the operator which one they're looking at. SELF_EARN — which needs no
 * network — still covers the caller's own phone throughout an outage.
 *
 * <p>Phone matching uses {@link #compareKey(String)}: whitespace-insensitive
 * only, the SAME key the SELF_EARN check uses (shared here so the two can
 * never drift). Both sides originate from the same platform, so formats agree
 * in practice; aggressive canonicalisation risks false positives against
 * legitimate customers.
 */
@Service
@Slf4j
public class StaffRegistry {

    /** authoritative=false marks a failed load — logged, never a match. */
    record Entry(Set<String> keys, boolean authoritative) {}

    private final UserServiceClient userServiceClient;
    private final Cache<UUID, Entry> cache;

    public StaffRegistry(UserServiceClient userServiceClient, LoyaltyProperties props) {
        this.userServiceClient = userServiceClient;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.earn().staffCacheSeconds()))
                .maximumSize(10_000)
                .build();
    }

    /**
     * True iff {@code phone} belongs to a staff member of {@code merchantId}
     * per the most recent authoritative snapshot. Null inputs and lookup
     * failures answer {@code false} (fail open — see class javadoc).
     */
    public boolean isStaffPhone(UUID merchantId, String phone) {
        String key = compareKey(phone);
        if (merchantId == null || key == null) {
            return false;
        }
        Entry entry = cache.get(merchantId, this::load);
        return entry != null && entry.keys().contains(key);
    }

    /**
     * Load {@code merchantId}'s staff snapshot into the cache if it is not
     * there already, so the guard that follows reads memory instead of calling
     * user-service (2s connect / 5s read). Called BEFORE the caller opens its
     * transaction — the guards themselves run inside one, after rows have been
     * inserted or locked (the earn's PENDING enrolment, the QR row, the
     * purchase order), and a cache miss there held those for the whole
     * round-trip.
     *
     * <p>Same loader and same fail-open as {@link #isStaffPhone}: a failed
     * lookup caches the non-authoritative empty set exactly as a miss inside
     * the guard would. <b>Never throws</b> — it is an optimisation, and a
     * failure here must leave the request to run exactly as it did before (the
     * guard then loads on its own, or reads the cached failure).
     */
    public void warm(UUID merchantId) {
        if (merchantId == null) {
            return;
        }
        try {
            cache.get(merchantId, this::load);
        } catch (RuntimeException e) {
            log.warn("Staff registry pre-load for merchant {} failed; the guard will load on demand: {}",
                    merchantId, e.toString());
        }
    }

    /** Whether a snapshot (authoritative or not) is cached for the merchant. */
    public boolean isCached(UUID merchantId) {
        return merchantId != null && cache.getIfPresent(merchantId) != null;
    }

    private Entry load(UUID merchantId) {
        return userServiceClient.merchantStaffPhones(merchantId)
                .map(phones -> new Entry(
                        phones.stream()
                                .map(StaffRegistry::compareKey)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toUnmodifiableSet()),
                        true))
                .orElseGet(() -> {
                    // Guard degraded for this merchant until the TTL lapses.
                    // Loud on purpose: a quiet fail-open is indistinguishable
                    // from the guard working.
                    log.warn("STAFF_RECIPIENT guard degraded: staff lookup for merchant {} "
                            + "failed/unavailable; treating as no staff until cache expiry",
                            merchantId);
                    return new Entry(Set.of(), false);
                });
    }

    /**
     * Whitespace-insensitive phone comparison key, shared by SELF_EARN and
     * STAFF_RECIPIENT so the two guards can never drift. Deliberately NOT a
     * full MSISDN canonicalisation — see class javadoc.
     */
    public static String compareKey(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        return phone.replaceAll("\\s+", "");
    }
}
