package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.security.CallerDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * Enables Spring Data JPA auditing so {@code @CreatedBy} / {@code @LastModifiedBy}
 * on {@link com.innbucks.loyaltyservice.entity.Auditable} subclasses are
 * auto-stamped with the acting principal on every INSERT/UPDATE.
 *
 * <p><b>Who is the auditor.</b> The caller's stable {@code user_uuid} (the same id
 * used across the fleet), read from the authenticated principal via
 * {@link CallerDetails}. When the token carries no uuid claim we fall back to the
 * JWT subject (email); for an unauthenticated / anonymous / system write we
 * return empty so the actor column is left null rather than stamped with a
 * misleading value (e.g. {@code anonymousUser}).
 *
 * <p>No {@code DateTimeProvider} is configured: the audited timestamps are
 * {@link java.time.Instant}, which Spring Data's default provider yields in UTC.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()
                    || auth instanceof AnonymousAuthenticationToken) {
                return Optional.empty();
            }
            UUID uuid = CallerDetails.currentUserId();
            if (uuid != null) {
                return Optional.of(uuid.toString());
            }
            String name = auth.getName();
            return (name == null || name.isBlank()) ? Optional.empty() : Optional.of(name);
        };
    }
}
