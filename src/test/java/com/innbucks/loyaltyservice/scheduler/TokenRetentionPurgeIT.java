package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.LoyaltyRefreshToken;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.entity.QrToken;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyRefreshTokenRepository;
import com.innbucks.loyaltyservice.repository.PhoneRegistrationRepository;
import com.innbucks.loyaltyservice.repository.QrTokenRepository;
import com.innbucks.loyaltyservice.service.LoyaltySessionService;
import com.innbucks.loyaltyservice.service.QrService;
import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retention purge against real Postgres: which rows go, which must
 * survive, and that the surviving ones still do their job.
 *
 * <p>The load-bearing case is {@link #spentRowOfALiveChain_survives_andStillTripsReuseDetection}:
 * a refresh token that was rotated (spent) long ago, whose own window closed,
 * but whose chain is still alive. Deleting it would make a replay of it an
 * ordinary "unknown" refusal and leave a thief's copy of the chain's tip
 * working; it must survive the purge and still revoke the chain when presented.
 *
 * <p>The Postgres container is shared with other IT classes and never
 * truncated, so a run here may also delete their long-dead rows; assertions are
 * about this test's own rows.
 */
class TokenRetentionPurgeIT extends PostgresIntegrationTestBase {

    private static final int RETENTION_DAYS = 90;

    @Autowired TokenRetentionPurgeJob job;
    @Autowired LoyaltySessionService sessions;
    @Autowired LoyaltyRefreshTokenRepository refreshTokens;
    @Autowired PhoneRegistrationRepository registrations;
    @Autowired QrTokenRepository qrTokens;
    @Autowired QrService qrService;
    @Autowired LoyaltyMetrics metrics;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MeterRegistry meterRegistry;

    // ------------------------------------------------------------------ fixtures

    private static String uniquePhone() {
        return "+26377" + String.format("%09d", System.nanoTime() % 1_000_000_000L);
    }

    private String registeredPhone() {
        String phone = uniquePhone();
        PhoneRegistration r = new PhoneRegistration();
        r.setPhoneNumber(phone);
        r.setRegisteredAt(Instant.now());
        r.setSource(PhoneRegistration.Source.TICKETING_OTP);
        r.setCreatedAt(Instant.now());
        registrations.save(r);
        return phone;
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    /** Moves a refresh row's window, as if it had been issued long ago. */
    private void setWindow(String token, Instant issuedAt, Instant expiresAt) {
        jdbc.update("UPDATE loyalty_refresh_tokens SET issued_at = ?, expires_at = ? WHERE token_hash = ?",
                ts(issuedAt), ts(expiresAt), hashOf(token));
    }

    private void setRevokedAt(UUID chainId, Instant revokedAt) {
        jdbc.update("UPDATE loyalty_refresh_tokens SET revoked_at = ? WHERE chain_id = ?",
                ts(revokedAt), chainId);
    }

    private LoyaltyRefreshToken row(String token) {
        return refreshTokens.findByTokenHash(hashOf(token)).orElseThrow();
    }

    private boolean exists(String token) {
        return refreshTokens.findByTokenHash(hashOf(token)).isPresent();
    }

    private int rowsInChain(UUID chainId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM loyalty_refresh_tokens WHERE chain_id = ?",
                Integer.class, chainId);
        return n == null ? 0 : n;
    }

    /** The production hash (package-private there); SHA-256 hex of the presented token. */
    private static String hashOf(String token) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private QrToken qr(Instant expiresAt, Instant usedAt) {
        QrToken q = new QrToken();
        q.setTenantId(UUID.randomUUID());
        q.setSourceType(QrToken.SourceType.MERCHANT);
        q.setSourceId(UUID.randomUUID());
        q.setTransactionType(TransactionType.PURCHASE);
        q.setAmount(new BigDecimal("40.0000"));
        q.setToken("purge-it-" + UUID.randomUUID());
        q.setSignature("sig");
        q.setExpiresAt(expiresAt);
        q.setUsedAt(usedAt);
        if (usedAt != null) {
            q.setTransactionId(UUID.randomUUID());
            q.setPointsAwarded(new BigDecimal("4.0000"));
        }
        q.setCreatedAt(expiresAt.minus(5, ChronoUnit.MINUTES));
        return qrTokens.save(q);
    }

    // ------------------------------------------------------------ refresh chains

    @Test
    @DisplayName("a SPENT row of a LIVE chain survives the purge and is still detected as reuse afterwards")
    void spentRowOfALiveChain_survives_andStillTripsReuseDetection() {
        Instant now = Instant.now();
        String phone = registeredPhone();
        String first = sessions.start(phone, "loyalty-otp").refreshToken();
        String tip = sessions.refresh(first).refreshToken();   // `first` is now spent
        UUID chain = row(first).getChainId();

        // `first` was issued and spent long ago: its own window closed far past
        // the retention period. The chain itself is alive through `tip`.
        Instant longAgo = now.minus(400, ChronoUnit.DAYS);
        setWindow(first, longAgo, longAgo.plus(90, ChronoUnit.DAYS));
        assertThat(row(first).getUsedAt()).isNotNull();

        job.run(now);

        assertThat(exists(first)).as("spent row of a live chain").isTrue();
        assertThat(exists(tip)).as("the chain's live tip").isTrue();

        // Still the tripwire: presenting the spent token revokes the whole chain.
        assertThatThrownBy(() -> sessions.refresh(first))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SESSION_REFRESH_REJECTED"));
        assertThat(row(tip).getRevokedAt()).as("tip revoked by reuse detection").isNotNull();
        assertThat(row(tip).getRevokedReason()).isEqualTo("reuse_detected");
        assertThatThrownBy(() -> sessions.refresh(tip)).isInstanceOf(LoyaltyException.class);
        assertThat(rowsInChain(chain)).isEqualTo(2);
    }

    @Test
    @DisplayName("a refresh refused because the phone's registration was revoked COMMITS the chain revocation")
    void registrationRevokedRefusal_commitsTheRevocation() {
        String phone = registeredPhone();
        String tip = sessions.start(phone, "loyalty-otp").refreshToken();
        jdbc.update("UPDATE phone_registrations SET revoked_at = now(), revoked_reason = 'it' WHERE phone_number = ?",
                phone);

        assertThatThrownBy(() -> sessions.refresh(tip)).isInstanceOf(LoyaltyException.class);

        assertThat(row(tip).getRevokedAt()).isNotNull();
        assertThat(row(tip).getRevokedReason()).isEqualTo("registration_revoked");
    }

    @Test
    @DisplayName("a chain whose every row EXPIRED more than the retention period ago is deleted whole")
    void expiredChainBeyondRetention_isDeletedWhole() {
        Instant now = Instant.now();
        String phone = registeredPhone();
        String first = sessions.start(phone, "loyalty-otp").refreshToken();
        String tip = sessions.refresh(first).refreshToken();
        UUID chain = row(first).getChainId();

        Instant abandoned = now.minus(RETENTION_DAYS + 91L, ChronoUnit.DAYS);
        setWindow(first, abandoned.minus(91, ChronoUnit.DAYS), abandoned.minus(1, ChronoUnit.DAYS));
        setWindow(tip, abandoned.minus(90, ChronoUnit.DAYS), abandoned);

        double before = metricCount();
        TokenRetentionPurgeJob.Outcome out = job.run(now);

        assertThat(rowsInChain(chain)).isZero();
        assertThat(out.refreshTokens().rows()).isGreaterThanOrEqualTo(2);
        assertThat(out.refreshTokens().complete()).isTrue();
        assertThat(metricCount() - before).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("an expired chain still inside the grace period is kept")
    void expiredChainWithinRetention_isKept() {
        Instant now = Instant.now();
        String tip = sessions.start(registeredPhone(), "loyalty-otp").refreshToken();
        UUID chain = row(tip).getChainId();
        // Lapsed 30 days ago: no longer renewable, but not yet past retention.
        setWindow(tip, now.minus(120, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS));

        job.run(now);

        assertThat(rowsInChain(chain)).isEqualTo(1);
    }

    @Test
    @DisplayName("a REVOKED chain goes once its revocation is older than retention, not before")
    void revokedChain_goesAfterRetentionFromRevocation() {
        Instant now = Instant.now();
        String oldRevoked = sessions.start(registeredPhone(), "loyalty-otp").refreshToken();
        String recentRevoked = sessions.start(registeredPhone(), "loyalty-otp").refreshToken();
        UUID oldChain = row(oldRevoked).getChainId();
        UUID recentChain = row(recentRevoked).getChainId();
        // Both rows' windows are still open (fresh), so only revocation can end them.
        setRevokedAt(oldChain, now.minus(RETENTION_DAYS + 1L, ChronoUnit.DAYS));
        setRevokedAt(recentChain, now.minus(10, ChronoUnit.DAYS));

        job.run(now);

        assertThat(rowsInChain(oldChain)).isZero();
        assertThat(rowsInChain(recentChain)).isEqualTo(1);
    }

    @Test
    @DisplayName("a revoked chain that grew a fresh, unrevoked row (refresh racing a revocation) survives")
    void chainWithAnyLiveRow_survivesWhole() {
        Instant now = Instant.now();
        String first = sessions.start(registeredPhone(), "loyalty-otp").refreshToken();
        String tip = sessions.refresh(first).refreshToken();
        UUID chain = row(first).getChainId();
        // `first` revoked long ago; `tip` unrevoked and live.
        jdbc.update("UPDATE loyalty_refresh_tokens SET revoked_at = ? WHERE token_hash = ?",
                ts(now.minus(400, ChronoUnit.DAYS)), hashOf(first));

        job.run(now);

        assertThat(rowsInChain(chain)).isEqualTo(2);
        assertThat(exists(tip)).isTrue();
    }

    @Test
    @DisplayName("several dead chains drain across batches, each batch its own transaction")
    void drainsAcrossBatches() {
        Instant now = Instant.now();
        Instant dead = now.minus(RETENTION_DAYS + 5L, ChronoUnit.DAYS);
        List<UUID> chains = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String t = sessions.start(registeredPhone(), "loyalty-otp").refreshToken();
            setWindow(t, dead.minus(90, ChronoUnit.DAYS), dead);
            chains.add(row(t).getChainId());
        }
        TokenRetentionPurgeJob small = new TokenRetentionPurgeJob(qrTokens, refreshTokens, metrics,
                new TransactionTemplate(transactionManager), true, RETENTION_DAYS, RETENTION_DAYS, 2, 100);

        TokenRetentionPurgeJob.Outcome out = small.run(now);

        assertThat(chains).allSatisfy(c -> assertThat(rowsInChain(c)).isZero());
        assertThat(out.refreshTokens().batches()).isGreaterThanOrEqualTo(3);
        assertThat(out.refreshTokens().complete()).isTrue();
    }

    private double metricCount() {
        Counter c = meterRegistry.find("loyalty.retention.purged")
                .tag("table", LoyaltyMetrics.TABLE_REFRESH_TOKENS).counter();
        return c == null ? 0 : c.count();
    }

    // ------------------------------------------------------------------- QR tokens

    @Test
    @DisplayName("QR: long-expired and long-consumed tokens go; recent and live ones stay")
    void qrRetention() {
        Instant now = Instant.now();
        Instant old = now.minus(RETENTION_DAYS + 1L, ChronoUnit.DAYS);
        QrToken expiredLongAgo = qr(old, null);
        QrToken consumedLongAgo = qr(old, old.minus(1, ChronoUnit.MINUTES));
        QrToken expiredRecently = qr(now.minus(10, ChronoUnit.DAYS), null);
        QrToken consumedRecently = qr(now.minus(10, ChronoUnit.DAYS), now.minus(10, ChronoUnit.DAYS).minusSeconds(60));
        QrToken live = qr(now.plus(5, ChronoUnit.MINUTES), null);

        TokenRetentionPurgeJob.Outcome out = job.run(now);

        assertThat(qrTokens.findById(expiredLongAgo.getId())).isEmpty();
        assertThat(qrTokens.findById(consumedLongAgo.getId())).isEmpty();
        assertThat(qrTokens.findById(expiredRecently.getId())).isPresent();
        assertThat(qrTokens.findById(consumedRecently.getId())).isPresent();
        assertThat(qrTokens.findById(live.getId())).isPresent();
        assertThat(out.qrTokens().rows()).isGreaterThanOrEqualTo(2);
        assertThat(out.qrTokens().complete()).isTrue();
    }

    @Test
    @DisplayName("QR: status of a purged token answers exactly like a token that never existed")
    void qrStatus_ofAPurgedToken_isTheUnknownTokenAnswer() {
        Instant old = Instant.now().minus(RETENTION_DAYS + 1L, ChronoUnit.DAYS);
        QrToken purged = qr(old, old.minus(1, ChronoUnit.MINUTES));
        assertThat(qrTokens.findByToken(purged.getToken())).isPresent();

        job.run(Instant.now());

        LoyaltyException forPurged = catchStatus(purged.getTenantId(), purged.getToken());
        LoyaltyException forUnknown = catchStatus(purged.getTenantId(), "never-issued-" + UUID.randomUUID());
        assertThat(forPurged.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(forPurged.getCode()).isEqualTo("NOT_FOUND");
        assertThat(forPurged.getMessage()).isEqualTo("This QR code is invalid or has expired.");
        assertThat(forPurged.getStatus()).isEqualTo(forUnknown.getStatus());
        assertThat(forPurged.getCode()).isEqualTo(forUnknown.getCode());
        assertThat(forPurged.getMessage()).isEqualTo(forUnknown.getMessage());
    }

    private LoyaltyException catchStatus(UUID tenantId, String token) {
        try {
            qrService.status(tenantId, token);
        } catch (LoyaltyException e) {
            return e;
        }
        throw new AssertionError("expected status() to refuse " + token);
    }

    // --------------------------------------------------------------- schema guards

    @Test
    @DisplayName("nothing references either table by foreign key — a new FK must revisit the purge")
    void noForeignKeyPointsAtThePurgedTables() {
        Integer fks = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                 WHERE contype = 'f'
                   AND confrelid IN ('qr_tokens'::regclass, 'loyalty_refresh_tokens'::regclass)
                """, Integer.class);
        assertThat(fks).isZero();
    }

    @Test
    @DisplayName("EXPLAIN: both purge statements can use their index")
    void purgePlansUseTheirIndexes() {
        String refreshPlan = explain(LoyaltyRefreshTokenRepository.PURGE_SQL);
        String qrPlan = explain(QrTokenRepository.PURGE_SQL);

        assertThat(refreshPlan).contains("idx_loyalty_refresh_purge_end");
        assertThat(refreshPlan).contains("idx_loyalty_refresh_chain");
        assertThat(qrPlan).contains("idx_qr_expires");
    }

    /**
     * The repository's own statement with literal binds, under
     * {@code enable_seqscan = off} for this transaction only: on a test-sized
     * table the planner would rightly prefer a scan, so this proves the index
     * MATCHES the predicate (an expression mismatch would still seq-scan), not
     * that it wins at any particular row count. EXPLAIN without ANALYZE does not
     * run the DELETE.
     */
    private String explain(String sql) {
        String literal = sql
                .replace(":cutoff", "TIMESTAMPTZ '2026-01-01 00:00:00+00'")
                .replace(":batch", "1000");
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("EXPLAIN " + literal, String.class));
        });
    }
}
