package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.QrToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface QrTokenRepository extends JpaRepository<QrToken, UUID> {

    /** {@link #purgeFinishedBefore}'s statement — a constant so a test can EXPLAIN exactly it. */
    String PURGE_SQL = """
            DELETE FROM qr_tokens
             WHERE id IN (SELECT q.id FROM qr_tokens q
                           WHERE q.expires_at < :cutoff
                             AND (q.used_at IS NULL OR q.used_at < :cutoff)
                           LIMIT :batch)
            """;

    Optional<QrToken> findByToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM QrToken q WHERE q.token = :token")
    Optional<QrToken> lockByToken(@Param("token") String token);

    /**
     * Retention purge (V60, {@code TokenRetentionPurgeJob}): deletes up to
     * {@code batch} tokens that expired AND, if consumed, were consumed before
     * {@code cutoff}. Returns the rows deleted; fewer than {@code batch} means
     * nothing else qualifies.
     *
     * <p>Nothing references a QR row: no foreign key points at
     * {@code qr_tokens}, and its own {@code transaction_id} is a pointer OUT to
     * the earn row, which stays. The only readers are consume, the issuer's
     * status read and the pre-consume staff pre-load, all keyed by the token
     * value, and all answer an absent row as an unknown token — the same 404
     * {@code NOT_FOUND} a long-dead token deserves. {@code used_at <= expires_at}
     * always holds (consume refuses after expiry); the explicit used_at term is
     * there so the rule never depends on it. Served by {@code idx_qr_expires}.
     */
    @Modifying
    @Query(value = PURGE_SQL, nativeQuery = true)
    int purgeFinishedBefore(@Param("cutoff") Instant cutoff, @Param("batch") int batch);
}
