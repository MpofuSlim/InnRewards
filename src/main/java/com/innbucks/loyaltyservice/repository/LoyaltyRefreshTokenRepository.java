package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.LoyaltyRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LoyaltyRefreshTokenRepository extends JpaRepository<LoyaltyRefreshToken, UUID> {

    /** {@link #purgeChainsEndedBefore}'s statement — a constant so a test can EXPLAIN exactly it. */
    String PURGE_SQL = """
            DELETE FROM loyalty_refresh_tokens
             WHERE chain_id IN (
                   SELECT d.chain_id FROM loyalty_refresh_tokens d
                    WHERE COALESCE(d.revoked_at, d.expires_at) < :cutoff
                      AND NOT EXISTS (
                          SELECT 1 FROM loyalty_refresh_tokens l
                           WHERE l.chain_id = d.chain_id
                             AND COALESCE(l.revoked_at, l.expires_at) >= :cutoff)
                    LIMIT :batch)
            """;

    /**
     * The one read on the refresh path. Keyed by hash, because the token itself
     * is never stored.
     *
     * <p>Returns the row whatever its state — used, revoked or expired — on
     * purpose: an already-used row is not "not found", it is the reuse signal,
     * and collapsing the two here would silently discard the only theft
     * detection this design has.
     */
    Optional<LoyaltyRefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every unrevoked row of a chain — the response to a replayed token,
     * and to an explicit sign-out.
     *
     * <p>It severs the legitimate device too, and that is the intended
     * behaviour on a replay: two parties hold credentials from this chain and
     * nothing distinguishes them, so the safe move is to end both and let the
     * customer prove the phone again. Already-revoked rows are left untouched so
     * the first reason recorded — the one that explains the incident — is not
     * overwritten by a later sweep.
     */
    @Modifying
    @Query("""
            UPDATE LoyaltyRefreshToken t
               SET t.revokedAt = :now, t.revokedReason = :reason
             WHERE t.chainId = :chainId AND t.revokedAt IS NULL
            """)
    int revokeChain(@Param("chainId") UUID chainId,
                    @Param("now") Instant now,
                    @Param("reason") String reason);

    /**
     * Revokes every unrevoked chain of a phone — "sign this customer out
     * everywhere". The operator lever that the access token's TTL was standing
     * in for, and the companion to revoking the phone's registration.
     */
    @Modifying
    @Query("""
            UPDATE LoyaltyRefreshToken t
               SET t.revokedAt = :now, t.revokedReason = :reason
             WHERE t.phoneNumber = :phoneNumber AND t.revokedAt IS NULL
            """)
    int revokeAllForPhone(@Param("phoneNumber") String phoneNumber,
                          @Param("now") Instant now,
                          @Param("reason") String reason);

    /**
     * How many session chains the phone could still renew right now: chains
     * whose head row is unused, unrevoked and unexpired. What "signed in on N
     * devices" means to a support agent, and what a support sign-out ends.
     */
    @Query("""
            SELECT COUNT(DISTINCT t.chainId) FROM LoyaltyRefreshToken t
             WHERE t.phoneNumber = :phoneNumber
               AND t.revokedAt IS NULL
               AND t.usedAt IS NULL
               AND t.expiresAt > :now
            """)
    long countActiveChains(@Param("phoneNumber") String phoneNumber, @Param("now") Instant now);

    /**
     * Retention purge (V60, {@code TokenRetentionPurgeJob}): deletes every row of
     * up to {@code batch} DEAD chains. Returns the rows deleted; fewer than
     * {@code batch} means nothing else qualifies (each candidate row belongs to a
     * dead chain, and all of that chain's rows go, so a full batch always
     * deletes at least {@code batch} rows).
     *
     * <p><b>A chain is the unit, never a row.</b> Reuse detection needs every
     * SPENT row ({@code used_at} set) of a live chain: presenting one revokes the
     * whole chain. A spent row's own {@code expires_at} is earlier than its
     * successor's, and {@code LoyaltySessionService.refresh} checks {@code
     * used_at} BEFORE expiry, so a spent row past its own window is still a
     * live tripwire while its chain lives. Deleting it would turn a replay into
     * an ordinary "unknown" refusal and leave the thief's copy of the tip
     * working.
     *
     * <p>A row "ends" at {@code revoked_at} if revoked, else at {@code
     * expires_at}. A chain is dead before {@code cutoff} only when EVERY row of
     * it ended before {@code cutoff} — so a chain with any unrevoked row still
     * inside (or within the grace of) its window survives whole, including a
     * successor written by a refresh racing a revocation. Served by
     * {@code idx_loyalty_refresh_purge_end} (the expression must stay
     * character-identical to the index) and {@code idx_loyalty_refresh_chain}.
     */
    @Modifying
    @Query(value = PURGE_SQL, nativeQuery = true)
    int purgeChainsEndedBefore(@Param("cutoff") Instant cutoff, @Param("batch") int batch);
}
