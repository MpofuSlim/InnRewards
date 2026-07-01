package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.QrToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface QrTokenRepository extends JpaRepository<QrToken, UUID> {

    Optional<QrToken> findByToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM QrToken q WHERE q.token = :token")
    Optional<QrToken> lockByToken(@Param("token") String token);
}
