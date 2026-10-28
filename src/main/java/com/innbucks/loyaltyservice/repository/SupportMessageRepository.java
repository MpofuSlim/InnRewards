package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.SupportMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Support messages. Not append-only — a row is completed once, PENDING to SENT
 * or FAILED — but the completion is a guarded bulk UPDATE, and there is no
 * delete here: the only delete is the retention sweep's, of rows past 12 months.
 */
public interface SupportMessageRepository extends Repository<SupportMessage, UUID> {

    SupportMessage saveAndFlush(SupportMessage message);

    Optional<SupportMessage> findById(UUID id);

    /** Every attempt by one agent since {@code since}, all kinds — the per-agent limit. */
    long countByAgentUuidAndCreatedAtAfter(String agentUuid, Instant since);

    /**
     * The per-recipient limit: attempts to one number since {@code since}, all
     * kinds, where a FAILED attempt counts only if it is newer than
     * {@code failedSince}. A sent or pending one counts for the whole window.
     */
    @Query("""
            SELECT COUNT(m) FROM SupportMessage m
             WHERE m.recipientMsisdn = :msisdn
               AND m.createdAt > :since
               AND (m.outcome <> com.innbucks.loyaltyservice.entity.SupportMessage.Outcome.FAILED
                    OR m.createdAt > :failedSince)
            """)
    long countTowardRecipientLimit(@Param("msisdn") String recipientMsisdn,
                                   @Param("since") Instant since,
                                   @Param("failedSince") Instant failedSince);

    long countByRecipientMsisdn(String recipientMsisdn);

    Page<SupportMessage> findByRecipientMsisdnOrderByCreatedAtDesc(String recipientMsisdn, Pageable pageable);

    Page<SupportMessage> findAll(Specification<SupportMessage> spec, Pageable pageable);

    /**
     * Completes a PENDING row exactly once. The {@code outcome = PENDING} guard
     * makes a second completion a no-op (0 rows) rather than a rewrite of what
     * happened.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE SupportMessage m
               SET m.outcome = :outcome,
                   m.deliveredVia = :deliveredVia,
                   m.body = :body,
                   m.failureCode = :failureCode,
                   m.completedAt = :completedAt
             WHERE m.id = :id
               AND m.outcome = com.innbucks.loyaltyservice.entity.SupportMessage.Outcome.PENDING
            """)
    int complete(@Param("id") UUID id,
                 @Param("outcome") SupportMessage.Outcome outcome,
                 @Param("deliveredVia") SupportMessage.DeliveredVia deliveredVia,
                 @Param("body") String body,
                 @Param("failureCode") String failureCode,
                 @Param("completedAt") Instant completedAt);
}
