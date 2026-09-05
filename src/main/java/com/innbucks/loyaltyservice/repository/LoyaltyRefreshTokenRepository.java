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
}
