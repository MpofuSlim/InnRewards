package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.RedemptionRate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RedemptionRateRepository extends JpaRepository<RedemptionRate, UUID> {

    /**
     * The rate in force for {@code currency} at instant {@code at}: the row with
     * the greatest {@code effectiveFrom <= at}, ties broken by the later
     * {@code createdAt} (a same-instant correction wins over the row it fixes).
     * A future-dated row is excluded until its instant arrives, which is what
     * lets an operator schedule a change ahead of time.
     */
    @Query("""
            select r from RedemptionRate r
            where r.currency = :currency and r.effectiveFrom <= :at
            order by r.effectiveFrom desc, r.createdAt desc
            """)
    List<RedemptionRate> findInForce(@Param("currency") String currency,
                                     @Param("at") Instant at, Pageable pageable);

    default Optional<RedemptionRate> currentRate(String currency, Instant at) {
        return findInForce(currency, at, Pageable.ofSize(1)).stream().findFirst();
    }

    /** Full history for a currency, newest-effective first — the audit trail. */
    List<RedemptionRate> findByCurrencyOrderByEffectiveFromDescCreatedAtDesc(String currency);
}
