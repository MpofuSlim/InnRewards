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
 * delete: every attempt stays counted.
 */
public interface SupportMessageRepository extends Repository<SupportMessage, UUID> {

    SupportMessage saveAndFlush(SupportMessage message);

    Optional<SupportMessage> findById(UUID id);

    /** Every attempt by one agent since {@code since}, all kinds — the per-agent limit. */
    long countByAgentUuidAndCreatedAtAfter(String agentUuid, Instant since);

    /** Every attempt to one number since {@code since}, all kinds — the per-recipient limit. */
    long countByRecipientMsisdnAndCreatedAtAfter(String recipientMsisdn, Instant since);

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
