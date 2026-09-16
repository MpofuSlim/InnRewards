package com.innbucks.loyaltyservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Boot-time misconfiguration check for the {@code /loyalty/public/**} test
 * surface.
 *
 * <p>Scope is deliberately narrow: <b>a tenant pin that is set but unparseable</b>.
 * {@code PublicTestController.parseUuidOrNull} swallows the parse failure and
 * returns null, so a typo'd UUID behaves <em>identically</em> to an unset one —
 * the operator sets a value, the service ignores it, and nothing anywhere says
 * so. Boot is the only place that can tell them.
 *
 * <p>An earlier version of this class also reported "no pin set while phones span
 * tenants", because that combination used to refuse the points writes with
 * {@code AMBIGUOUS_TENANT}. It no longer does — {@code resolveActingProjection}
 * now picks a projection instead of refusing, since wallets are global per phone
 * and the choice changes only ledger attribution. That arm was removed rather
 * than left in place: a boot ERROR describing a failure that cannot happen is
 * worse than none, because it trains an operator to scroll past this line.
 *
 * <p>ERROR, never a boot failure — a test affordance must not stop a cell
 * starting. Does nothing at all when the surface is off, so a production cell is
 * untouched.
 */
@Component
@Slf4j
public class PublicTestProvisioningCheck {

    private final boolean enabled;
    private final String configuredTenantId;
    private final String apiKey;

    public PublicTestProvisioningCheck(
            // These bound `loyalty.public.test.*` while PublicTestController and
            // application.yaml use `loyalty.public-test.*`. The two agreed only
            // because both resolve from the same LOYALTY_PUBLIC_TEST_* environment
            // variable through relaxed binding — a YAML or @TestPropertySource
            // override of one silently left the other unset, so the check could
            // report on a configuration the controller was not running.
            @Value("${loyalty.public-test.enabled:false}") boolean enabled,
            @Value("${loyalty.public-test.tenant-id:}") String configuredTenantId,
            @Value("${loyalty.public-test.api-key:}") String apiKey) {
        this.enabled = enabled;
        this.configuredTenantId = configuredTenantId;
        this.apiKey = apiKey;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkPublicTestProvisioning() {
        if (!enabled) return;

        checkApiKey();

        String raw = configuredTenantId == null ? "" : configuredTenantId.trim();
        if (raw.isEmpty()) {
            log.info("Public test surface is enabled with no tenant pin; the acting projection is "
                    + "resolved per request (ACTIVE first, then oldest).");
            return;
        }

        try {
            UUID.fromString(raw);
            log.info("Public test surface is enabled and pinned to tenant {}.", raw);
        } catch (IllegalArgumentException ex) {
            log.error("Public test surface is MISCONFIGURED: LOYALTY_PUBLIC_TEST_TENANT_ID is set but "
                    + "is not a valid UUID, so it is SILENTLY IGNORED and the points writes behave as "
                    + "if no pin were configured. Fix the value or clear it.");
        }
    }

    /**
     * The half-provisioned state, called out the way the ZimSwitch rail's is:
     * the surface is switched ON but has no {@code x-api-key} to check, so
     * {@code PublicTestApiKeyFilter} refuses every call 503. That looks
     * identical to an outage from the client's side, and the operator's only
     * clue that it is a config gap rather than a broken service is this line.
     *
     * <p>ERROR, never a boot failure, for the same reason as the rest of this
     * class — a test affordance must not stop a cell starting.
     */
    private void checkApiKey() {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) {
            log.error("Public test surface is HALF-PROVISIONED: /loyalty/public/** is ENABLED but "
                    + "LOYALTY_PUBLIC_TEST_API_KEY is blank, so every call is refused 503. Set the key "
                    + "(openssl rand -base64 32) in this host's gitignored cell.<iso>.local.env and "
                    + "publish the same value to the app's Firebase Remote Config, or switch the "
                    + "surface off.");
        } else {
            log.info("Public test surface is enabled and gated by an x-api-key.");
        }
    }
}
