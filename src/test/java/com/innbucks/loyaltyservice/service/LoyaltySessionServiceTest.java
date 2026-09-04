package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.LoyaltyRefreshToken;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyRefreshTokenRepository;
import com.innbucks.loyaltyservice.security.LoyaltySessionIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The refresh path (V43) — what lets a customer's loyalty session outlive its
 * 12h access token without a second SMS.
 *
 * <p><b>What is actually at stake here.</b> The access token carries no
 * {@code userId}, so the fleet's tokenVersion denylist cannot reach it and its
 * TTL was the ONLY thing that ever ended it. The obvious fixes — a longer TTL,
 * or letting a token renew itself — both turn it into a bearer credential
 * nothing can withdraw, and a stolen copy would renew alongside the legitimate
 * one with no moment at which the theft became visible. The whole point of a
 * separate, rotating, server-recorded credential is that a second holder
 * becomes DETECTABLE. {@link #reusingASpentTokenRevokesTheWholeChain()} is that
 * property; if it ever goes green by accident this design has lost its reason
 * to exist.
 */
class LoyaltySessionServiceTest {

    private static final String PHONE = "+263777224008";
    private static final String OTP_SCOPE = "loyalty-otp";

    private LoyaltyRefreshTokenRepository tokens;
    private LoyaltySessionIssuer issuer;
    private UserService users;
    private LoyaltySessionService service;

    /** Everything {@code save()} was handed, in order — the persisted rows. */
    private final List<LoyaltyRefreshToken> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tokens = mock(LoyaltyRefreshTokenRepository.class);
        issuer = mock(LoyaltySessionIssuer.class);
        users = mock(UserService.class);

        when(issuer.issue(anyString())).thenReturn("access-token");
        when(issuer.ttlSeconds()).thenReturn(43200L);
        when(users.isPhoneRegistered(PHONE)).thenReturn(true);

        saved.clear();
        when(tokens.save(any(LoyaltyRefreshToken.class))).thenAnswer(inv -> {
            LoyaltyRefreshToken row = inv.getArgument(0);
            saved.removeIf(existing -> existing.getId().equals(row.getId()));
            saved.add(row);
            return row;
        });
        when(tokens.findByTokenHash(anyString())).thenAnswer(inv -> {
            String hash = inv.getArgument(0);
            return saved.stream().filter(r -> hash.equals(r.getTokenHash())).findFirst();
        });

        service = new LoyaltySessionService(tokens, issuer, users, mock(LoyaltyMetrics.class), 90);
    }

    // ---------------------------------------------------------------- start

    @Test
    @DisplayName("start opens a chain and hands back both credentials")
    void startOpensAChain() {
        LoyaltySessionService.Session session = service.start(PHONE, OTP_SCOPE);

        assertThat(session.phoneNumber()).isEqualTo(PHONE);
        assertThat(session.accessToken()).isEqualTo("access-token");
        assertThat(session.expiresInSeconds()).isEqualTo(43200L);
        assertThat(session.refreshToken()).startsWith("LRT-");
        assertThat(session.refreshExpiresInSeconds()).isEqualTo(Duration.ofDays(90).toSeconds());

        assertThat(saved).hasSize(1);
        LoyaltyRefreshToken row = saved.getFirst();
        assertThat(row.getPhoneNumber()).isEqualTo(PHONE);
        assertThat(row.getOriginScope()).isEqualTo(OTP_SCOPE);
        assertThat(row.getUsedAt()).isNull();
        assertThat(row.getRevokedAt()).isNull();
        assertThat(row.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(89)));
    }

    @Test
    @DisplayName("SECURITY: the token itself is never stored — only its SHA-256")
    void onlyTheHashIsStored() {
        String refreshToken = service.start(PHONE, OTP_SCOPE).refreshToken();

        LoyaltyRefreshToken row = saved.getFirst();
        assertThat(row.getTokenHash())
                .isNotEqualTo(refreshToken)
                .doesNotContain(refreshToken)
                .hasSize(64)
                .matches("[0-9a-f]{64}");
        assertThat(row.getTokenHash()).isEqualTo(LoyaltySessionService.hash(refreshToken));
    }

    @Test
    @DisplayName("two chains for the same phone are independent")
    void chainsAreIndependent() {
        UUID first = chainOf(service.start(PHONE, OTP_SCOPE).refreshToken());
        UUID second = chainOf(service.start(PHONE, OTP_SCOPE).refreshToken());

        assertThat(first).isNotEqualTo(second);
    }

    // -------------------------------------------------------------- refresh

    @Test
    @DisplayName("refresh rotates: the presented token is spent and a successor is issued")
    void refreshRotates() {
        String first = service.start(PHONE, OTP_SCOPE).refreshToken();

        LoyaltySessionService.Session renewed = service.refresh(first);

        assertThat(renewed.refreshToken()).isNotEqualTo(first);
        assertThat(renewed.phoneNumber()).isEqualTo(PHONE);
        assertThat(saved).hasSize(2);

        LoyaltyRefreshToken presented = rowFor(first);
        LoyaltyRefreshToken successor = rowFor(renewed.refreshToken());
        assertThat(presented.getUsedAt()).isNotNull();
        assertThat(successor.getUsedAt()).isNull();
        // Same family: reuse detection revokes a chain, so a rotation that
        // started a new one would leave the retired token's family unreachable.
        assertThat(successor.getChainId()).isEqualTo(presented.getChainId());
    }

    @Test
    @DisplayName("a chain still names the proof channel it came from after rotating")
    void originScopeSurvivesRotation() {
        String first = service.start(PHONE, "loyalty-session").refreshToken();
        String second = service.refresh(first).refreshToken();

        assertThat(rowFor(second).getOriginScope()).isEqualTo("loyalty-session");
    }

    @Test
    @DisplayName("SECURITY: reusing a spent token revokes the WHOLE chain, not just that row")
    void reusingASpentTokenRevokesTheWholeChain() {
        String stolen = service.start(PHONE, OTP_SCOPE).refreshToken();
        service.refresh(stolen);                       // the real device rotates
        UUID chainId = rowFor(stolen).getChainId();

        // The attacker replays the copy they captured before the rotation.
        assertThatThrownBy(() -> service.refresh(stolen))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));

        // The successor the attacker does NOT hold is killed too — that is the
        // point. Nothing distinguishes the two holders, so both are signed out
        // and the customer proves the phone again.
        verify(tokens).revokeChain(eq(chainId), any(Instant.class), eq("reuse_detected"));
    }

    @Test
    @DisplayName("an unknown token is refused and writes nothing")
    void unknownTokenIsRefused() {
        assertThatThrownBy(() -> service.refresh("LRT-nothing-like-this"))
                .isInstanceOf(LoyaltyException.class);

        assertThat(saved).isEmpty();
        verify(tokens, never()).revokeChain(any(), any(), anyString());
    }

    @Test
    @DisplayName("a blank or null token is refused without a repository lookup")
    void blankTokenIsRefused() {
        assertThatThrownBy(() -> service.refresh("  ")).isInstanceOf(LoyaltyException.class);
        assertThatThrownBy(() -> service.refresh(null)).isInstanceOf(LoyaltyException.class);

        verify(tokens, never()).findByTokenHash(anyString());
    }

    @Test
    @DisplayName("a revoked token is refused")
    void revokedTokenIsRefused() {
        String token = service.start(PHONE, OTP_SCOPE).refreshToken();
        rowFor(token).setRevokedAt(Instant.now());

        assertThatThrownBy(() -> service.refresh(token)).isInstanceOf(LoyaltyException.class);
    }

    @Test
    @DisplayName("an expired token is refused — the bound on an abandoned device")
    void expiredTokenIsRefused() {
        String token = service.start(PHONE, OTP_SCOPE).refreshToken();
        rowFor(token).setExpiresAt(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.refresh(token)).isInstanceOf(LoyaltyException.class);
    }

    @Test
    @DisplayName("SECURITY: revoking the phone's registration ends the chain at the next refresh")
    void revokedRegistrationEndsTheChain() {
        String token = service.start(PHONE, OTP_SCOPE).refreshToken();
        UUID chainId = rowFor(token).getChainId();
        // The operator revoked the V40 registration — a refresh continues a
        // proof, so it must not outlive the proof being withdrawn.
        when(users.isPhoneRegistered(PHONE)).thenReturn(false);

        assertThatThrownBy(() -> service.refresh(token)).isInstanceOf(LoyaltyException.class);

        verify(tokens).revokeChain(eq(chainId), any(Instant.class), eq("registration_revoked"));
    }

    @Test
    @DisplayName("every refusal answers with the same opaque code")
    void everyRefusalIsOpaque() {
        String revoked = service.start(PHONE, OTP_SCOPE).refreshToken();
        rowFor(revoked).setRevokedAt(Instant.now());
        String expired = service.start(PHONE, OTP_SCOPE).refreshToken();
        rowFor(expired).setExpiresAt(Instant.now().minusSeconds(1));

        for (String token : List.of(revoked, expired, "LRT-unknown")) {
            assertThatThrownBy(() -> service.refresh(token))
                    .isInstanceOf(LoyaltyException.class)
                    .satisfies(e -> {
                        LoyaltyException le = (LoyaltyException) e;
                        assertThat(le.getCode()).isEqualTo("SESSION_REFRESH_REJECTED");
                        assertThat(le.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    });
        }
    }

    // ------------------------------------------------------------- sign-out

    @Test
    @DisplayName("sign-out revokes the chain")
    void signOutRevokesTheChain() {
        String token = service.start(PHONE, OTP_SCOPE).refreshToken();
        UUID chainId = rowFor(token).getChainId();

        service.signOut(token);

        verify(tokens).revokeChain(eq(chainId), any(Instant.class), eq("signed_out"));
    }

    @Test
    @DisplayName("sign-out with an unknown token succeeds silently — never an existence oracle")
    void signOutIsSilentOnUnknownTokens() {
        service.signOut("LRT-never-issued");
        service.signOut(null);

        verify(tokens, never()).revokeChain(any(), any(), anyString());
    }

    @Test
    @DisplayName("revokeAllForPhone signs the customer out everywhere")
    void revokeAllForPhone() {
        when(tokens.revokeAllForPhone(eq(PHONE), any(Instant.class), eq("fraud_hold"))).thenReturn(3);

        assertThat(service.revokeAllForPhone(PHONE, "fraud_hold")).isEqualTo(3);
    }

    // --------------------------------------------------------------- helpers

    private LoyaltyRefreshToken rowFor(String token) {
        return saved.stream()
                .filter(r -> LoyaltySessionService.hash(token).equals(r.getTokenHash()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for the presented token"));
    }

    private UUID chainOf(String token) {
        return rowFor(token).getChainId();
    }

    /** Guards the fake: {@code findByTokenHash} must return spent rows too, or
     *  reuse detection would silently degrade into "unknown token". */
    @Test
    @DisplayName("the lookup returns a spent row rather than empty")
    void lookupReturnsSpentRows() {
        String token = service.start(PHONE, OTP_SCOPE).refreshToken();
        service.refresh(token);

        Optional<LoyaltyRefreshToken> found = tokens.findByTokenHash(LoyaltySessionService.hash(token));
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getUsedAt()).isNotNull();
    }
}
