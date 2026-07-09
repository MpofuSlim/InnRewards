package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.security.CallerDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link JpaAuditingConfig#auditorAware()} resolution that stamps
 * {@code @CreatedBy}/{@code @LastModifiedBy} on {@code Auditable} entities.
 * Pure JUnit — no Spring context, no Docker/Testcontainers — so it runs
 * anywhere; the bean is a plain lambda over {@link SecurityContextHolder} +
 * {@link CallerDetails}.
 */
class JpaAuditingConfigTest {

    private final AuditorAware<String> auditor = new JpaAuditingConfig().auditorAware();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String email, UUID userId) {
        var token = new UsernamePasswordAuthenticationToken(
                email, null, AuthorityUtils.createAuthorityList("ROLE_MERCHANT_ADMIN"));
        // CallerDetails is the record loyalty's JwtFilter stashes on the auth details.
        token.setDetails(new CallerDetails(null, null, null, userId));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    void prefersUserUuidWhenPresent() {
        UUID uuid = UUID.fromString("99999999-8888-7777-6666-555555555555");
        authenticate("admin@innbucks.co.zw", uuid);
        assertThat(auditor.getCurrentAuditor()).contains(uuid.toString());
    }

    @Test
    void fallsBackToEmailWhenNoUserId() {
        authenticate("admin@innbucks.co.zw", null);
        assertThat(auditor.getCurrentAuditor()).contains("admin@innbucks.co.zw");
    }

    @Test
    void emptyWhenUnauthenticated() {
        SecurityContextHolder.clearContext();
        assertThat(auditor.getCurrentAuditor()).isEmpty();
    }

    @Test
    void emptyForAnonymousToken() {
        var anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        SecurityContextHolder.getContext().setAuthentication(anon);
        assertThat(auditor.getCurrentAuditor()).isEmpty();
    }
}
