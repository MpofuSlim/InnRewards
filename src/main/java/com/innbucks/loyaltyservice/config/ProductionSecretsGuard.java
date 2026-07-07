package com.innbucks.loyaltyservice.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Refuses to boot under a real DEPLOYMENT profile if any sensitive secret still
 * holds its {@code change-me-*} placeholder default from {@code application.yaml},
 * or if the HS256 signing key is too short. It's far too easy to deploy a
 * service with the JWT or HMAC secret unchanged; this fails fast at startup so
 * that mistake never reaches the wire.
 *
 * <p>"Deployment" = an active-profile set containing NO {@code dev/test/it/local}
 * profile (e.g. {@code prod}, {@code uat}, {@code staging}). A02-M3: this now
 * includes an EMPTY profile set — a container launched without
 * {@code SPRING_PROFILES_ACTIVE} is treated as a deployment and fails closed on
 * placeholder secrets instead of silently booting on them. Local dev / a stray
 * {@code java -jar} must opt out explicitly with a dev/test/local profile.
 * Stronger than gating on {@code prod} alone: a staging box, or a manifest that
 * sets some non-prod deployment profile, is covered too.
 */
@Configuration
@Slf4j
public class ProductionSecretsGuard {

    // Add to this list when new sensitive properties land. Keep it tight —
    // only secrets whose disclosure would compromise the service, not every
    // tunable.
    private static final List<String> SECRETS_TO_CHECK = List.of(
            "jwt.secret",
            "innbucks.internal-api-token",
            "loyalty.voucher.secret",
            "loyalty.qr.secret",
            "whatsapp.api-key"
    );

    private static final String PLACEHOLDER_MARKER = "change-me";

    // Placeholder secrets are tolerated only under these (local dev + test) profiles.
    private static final Set<String> NON_DEPLOYMENT_PROFILES = Set.of("dev", "test", "it", "local");

    // HS256 requires a >= 32-byte key (Keys.hmacShaKeyFor throws below that);
    // fail fast at boot rather than at the first token sign.
    private static final int MIN_JWT_SECRET_LENGTH = 32;

    private final Environment env;

    public ProductionSecretsGuard(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void verifyNoPlaceholderSecrets() {
        String[] active = env.getActiveProfiles();
        // A02-M3: fail-CLOSED. Deployment = the active-profile set contains NO
        // non-deployment (dev/test/it/local) profile — which now INCLUDES the
        // EMPTY set. A container launched without SPRING_PROFILES_ACTIVE is
        // treated as a deployment and must not boot on change-me placeholders;
        // local dev / a stray `java -jar` must opt out explicitly with a
        // dev/test/local profile. Every @SpringBootTest already sets an explicit
        // test/it profile, so the suite is unaffected.
        boolean deployment = Arrays.stream(active).noneMatch(NON_DEPLOYMENT_PROFILES::contains);
        if (!deployment) {
            log.info("Secrets guard skipped (non-deployment profile: {})", Arrays.toString(active));
            return;
        }

        List<String> offenders = new ArrayList<>();
        for (String key : SECRETS_TO_CHECK) {
            String value = env.getProperty(key);
            if (value != null && value.contains(PLACEHOLDER_MARKER)) {
                offenders.add(key);
            }
        }
        String jwt = env.getProperty("jwt.secret");
        if (jwt != null && jwt.length() < MIN_JWT_SECRET_LENGTH && !offenders.contains("jwt.secret")) {
            offenders.add("jwt.secret (too short: needs >= " + MIN_JWT_SECRET_LENGTH + " chars)");
        }
        // A02: Redis holds session-revocation + rate-limit state. If this service
        // is configured against Redis but the password is blank under a
        // deployment profile, refuse to boot — require Redis AUTH. compose/k8s
        // already provide REDIS_PASSWORD; this makes a forgotten one fail fast
        // instead of silently running an unauthenticated Redis that an
        // in-cluster attacker could use to tamper with revocation state.
        String redisPassword = env.getProperty("spring.data.redis.password");
        if (redisPassword != null && redisPassword.isBlank()) {
            offenders.add("spring.data.redis.password (blank — Redis AUTH required under deployment)");
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start under deployment profile " + Arrays.toString(active) +
                    ": these secrets still hold placeholder/weak values: " + offenders +
                    ". Override them via env vars (JWT_SECRET, INTERNAL_API_TOKEN, " +
                    "LOYALTY_VOUCHER_SECRET, LOYALTY_QR_SECRET) before deploying."
            );
        }
        log.info("Secrets guard passed for profile {} ({} keys verified)",
                Arrays.toString(active), SECRETS_TO_CHECK.size());
    }
}
