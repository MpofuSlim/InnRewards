package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.SupportActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only by SHAPE: a plain {@link Repository} exposing save and reads, and
 * nothing that updates or deletes. The oversight feed is only worth anything if
 * no code path can rewrite it.
 */
public interface SupportActivityRepository extends Repository<SupportActivity, UUID> {

    SupportActivity save(SupportActivity activity);

    Optional<SupportActivity> findById(UUID id);

    /**
     * Resolves a lookup session: the {@code CUSTOMER_LOOKUP} row with this id,
     * made by THIS agent, still inside the TTL. Every other case — someone
     * else's lookup, an expired one, an id that is not a lookup at all — is the
     * same empty answer, so the endpoint cannot be used as an oracle.
     */
    @Query("""
            SELECT a FROM SupportActivity a
             WHERE a.id = :id
               AND a.action = :action
               AND a.agentUuid = :agentUuid
               AND a.createdAt > :notBefore
            """)
    Optional<SupportActivity> findLiveLookup(@Param("id") UUID id,
                                             @Param("action") String action,
                                             @Param("agentUuid") String agentUuid,
                                             @Param("notBefore") Instant notBefore);

    /**
     * The supervisor's feed. Filters are appended as Criteria predicates, one per
     * PRESENT filter — never a {@code (:x IS NULL OR ...)} query, whose untyped
     * null bind Postgres cannot type.
     */
    Page<SupportActivity> findAll(Specification<SupportActivity> spec, Pageable pageable);
}
