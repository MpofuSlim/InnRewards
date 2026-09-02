package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.ExchangeRate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {

    /**
     * The latest in-force TENANT-OVERRIDE row for {@code (tenantId, currency)}
     * at instant {@code at}: greatest {@code effectiveFrom <= at}, ties broken
     * by the later {@code createdAt} (a same-instant correction wins over the
     * row it fixes). A future-dated row is excluded until its instant arrives,
     * which is what lets an operator schedule a change ahead of time.
     */
    @Query("""
            select r from ExchangeRate r
            where r.tenantId = :tenantId and r.currency = :currency and r.effectiveFrom <= :at
            order by r.effectiveFrom desc, r.createdAt desc
            """)
    List<ExchangeRate> findInForceForTenant(@Param("tenantId") UUID tenantId,
                                            @Param("currency") String currency,
                                            @Param("at") Instant at, Pageable pageable);

    /**
     * The latest in-force PLATFORM row (tenantId null — the inherited default)
     * for {@code currency} from the given {@code source}, at instant {@code at}.
     * Queried per source because platform ADMIN beats platform FEED regardless
     * of recency: a rate a person set stays in force until a person supersedes
     * it — the automated bank feed never silently out-dates a human decision.
     */
    @Query("""
            select r from ExchangeRate r
            where r.tenantId is null and r.currency = :currency
              and r.source = :source and r.effectiveFrom <= :at
            order by r.effectiveFrom desc, r.createdAt desc
            """)
    List<ExchangeRate> findInForcePlatform(@Param("currency") String currency,
                                           @Param("source") ExchangeRate.Source source,
                                           @Param("at") Instant at, Pageable pageable);

    /**
     * The rate in force for {@code (tenantId, currency)} at {@code at}, resolved
     * with the override precedence (mirrors the loyalty_rules merchant-beats-
     * global inheritance):
     *
     * <ol>
     *   <li>tenant override ({@code tenantId} row) — a rate the tenant set;</li>
     *   <li>platform ADMIN — a rate a SUPER_ADMIN set for everyone;</li>
     *   <li>platform FEED — the automated "bank rate" default.</li>
     * </ol>
     *
     * i.e. the bank rate applies only when nobody set one. {@code tenantId}
     * null resolves the platform scopes alone (2 → 3).
     */
    default Optional<ExchangeRate> currentRate(UUID tenantId, String currency, Instant at) {
        Pageable one = Pageable.ofSize(1);
        if (tenantId != null) {
            Optional<ExchangeRate> override =
                    findInForceForTenant(tenantId, currency, at, one).stream().findFirst();
            // A CLEARED row is a tombstone (V39): the tenant deliberately went
            // back to the platform rate, so fall through instead of returning it.
            // Note this checks only the LATEST in-force tenant row — an older
            // tombstone behind a newer override must NOT suppress that override,
            // which is exactly what "latest wins" already gives us.
            if (override.isPresent() && !override.get().isCleared()) return override;
        }
        Optional<ExchangeRate> admin =
                findInForcePlatform(currency, ExchangeRate.Source.ADMIN, at, one).stream().findFirst();
        if (admin.isPresent()) return admin;
        return findInForcePlatform(currency, ExchangeRate.Source.FEED, at, one).stream().findFirst();
    }

    /** Full history for a currency across ALL scopes, newest-effective first —
     *  the audit trail (rows carry tenantId + source so a reader can tell a
     *  tenant override from the platform default from the feed). */
    List<ExchangeRate> findByCurrencyOrderByEffectiveFromDescCreatedAtDesc(String currency);
}
