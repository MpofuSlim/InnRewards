package com.innbucks.loyaltyservice.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to boot under the {@code prod} profile if any sensitive secret still
 * holds its {@code change-me-*} placeholder default from {@code application.yaml}.
 * It's far too easy to deploy a service with the JWT or HMAC secret unchanged;
 * this fails fast at startup so that mistake never reaches the wire.
 *
 * <p>Only active under {@code -Dspring.profiles.active=prod}, so dev/test runs
 * keep working against the convenient placeholder values.
 */
@Configuration
@Profile("prod")
@Slf4j
public class ProductionSecretsGuard {

    // Add to this list when new sensitive properties land. Keep it tight —
    // only secrets whose disclosure would compromise the service, not every
    // tunable.
    private static final List<String> SECRETS_TO_CHECK = List.of(
            "jwt.secret",
            "innbucks.internal-api-token",
            "loyalty.voucher.secret",
            "loyalty.qr.secret"
    );

    private static final String PLACEHOLDER_MARKER = "change-me";

    private final Environment env;

    public ProductionSecretsGuard(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void verifyNoPlaceholderSecrets() {
        List<String> offenders = new ArrayList<>();
        for (String key : SECRETS_TO_CHECK) {
            String value = env.getProperty(key);
            if (value != null && value.contains(PLACEHOLDER_MARKER)) {
                offenders.add(key);
            }
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start under 'prod' profile: the following secrets " +
                    "still have placeholder defaults containing '" + PLACEHOLDER_MARKER +
                    "': " + offenders + ". Override them via env vars (JWT_SECRET, " +
                    "INTERNAL_API_TOKEN, LOYALTY_VOUCHER_SECRET, LOYALTY_QR_SECRET) " +
                    "before booting in production."
            );
        }
        log.info("Production secrets check passed ({} keys verified)", SECRETS_TO_CHECK.size());
    }
}
