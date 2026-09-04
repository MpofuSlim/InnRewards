package com.innbucks.loyaltyservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Boot-time provisioning check for the partner registration endpoint (V40).
 *
 * <p>The failure this exists to catch is HALF-PROVISIONING: the feature switched
 * on in one config source while its key material lives in another that was never
 * updated. The ZW cell makes that easy — services read their whole environment
 * from a ConfigMap plus a Secret, so a flag committed in the shared file and a
 * key that only ever existed in the gitignored per-host file produce a cell that
 * looks live and refuses every single call with 503. Exactly how the ZimSwitch
 * card rail shipped.
 *
 * <p>ERROR, never a boot failure: a partner integration must not be able to stop
 * a cell starting. Silent when the feature is off, so a production cell that has
 * not opted in sees nothing.
 *
 * <p>Binds the same property spellings the controller and the verifier use. Do
 * not introduce a variant spelling here — the {@code loyalty.public-test.*} vs
 * {@code loyalty.public.test.*} drift in {@code PublicTestProvisioningCheck}
 * agrees only by accident of environment-variable relaxed binding, and a YAML
 * override of one silently leaves the other unset.
 */
@Component
@Slf4j
public class PartnerRegistrationProvisioningCheck {

    private final boolean enabled;
    private final String authMode;
    private final String partnerKey;
    private final String publicKey;
    private final String innbucksBaseUrl;
    private final String innbucksApiKey;
    private final String innbucksProbePath;

    public PartnerRegistrationProvisioningCheck(
            @Value("${loyalty.registration.partner.enabled:false}") boolean enabled,
            @Value("${loyalty.registration.partner.auth-mode:assertion}") String authMode,
            @Value("${loyalty.registration.partner.key:}") String partnerKey,
            @Value("${loyalty.registration.partner.public-key:}") String publicKey,
            @Value("${loyalty.registration.partner.innbucks.base-url:}") String innbucksBaseUrl,
            @Value("${loyalty.registration.partner.innbucks.api-key:}") String innbucksApiKey,
            @Value("${loyalty.registration.partner.innbucks.probe-path:}") String innbucksProbePath) {
        this.enabled = enabled;
        this.authMode = authMode == null ? "assertion" : authMode.trim().toLowerCase();
        this.partnerKey = partnerKey;
        this.publicKey = publicKey;
        this.innbucksBaseUrl = innbucksBaseUrl;
        this.innbucksApiKey = innbucksApiKey;
        this.innbucksProbePath = innbucksProbePath;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkPartnerRegistrationProvisioning() {
        if (!enabled) return;

        if (!"assertion".equals(authMode) && !"key".equals(authMode) && !"innbucks".equals(authMode)) {
            log.error("Partner registration is MISCONFIGURED: loyalty.registration.partner.auth-mode is "
                    + "'{}', which is none of 'assertion', 'key' or 'innbucks'. The endpoint falls back "
                    + "to assertion mode; set it explicitly.", authMode);
            return;
        }

        boolean provisioned = switch (authMode) {
            case "key" -> partnerKey != null && !partnerKey.isBlank();
            case "innbucks" -> innbucksBaseUrl != null && !innbucksBaseUrl.isBlank()
                    && innbucksApiKey != null && !innbucksApiKey.isBlank()
                    && innbucksProbePath != null && !innbucksProbePath.isBlank();
            default -> publicKey != null && !publicKey.isBlank();
        };

        if (!provisioned) {
            String missing = switch (authMode) {
                case "key" -> "LOYALTY_PARTNER_REGISTRATION_KEY";
                case "innbucks" -> "LOYALTY_PARTNER_REGISTRATION_INNBUCKS_BASE_URL / _API_KEY / _PROBE_PATH";
                default -> "LOYALTY_PARTNER_REGISTRATION_PUBLIC_KEY";
            };
            log.error("Partner registration is HALF-PROVISIONED: enabled in {} mode but {} is blank, so "
                    + "every registration call is refused 503 and app customers stay unable to spend. "
                    + "Provision it in this host's cell.<iso>.local.env, or set "
                    + "LOYALTY_PARTNER_REGISTRATION_ENABLED=false.", authMode, missing);
            return;
        }

        switch (authMode) {
            case "key" -> log.warn("Partner registration is enabled in SHARED-KEY mode. Whoever holds "
                    + "LOYALTY_PARTNER_REGISTRATION_KEY can register ANY phone number; it must live only "
                    + "in the partner's server-side config and never in a mobile client. Prefer "
                    + "auth-mode=assertion once the partner can sign.");
            case "innbucks" -> {
                log.info("Partner registration is enabled in innbucks mode: a claimed phone is proved by "
                        + "reading it under the caller's own user token at {}{}.",
                        innbucksBaseUrl, innbucksProbePath);
                // The one misconfiguration that would look healthy and prove
                // nothing: pointing the probe at the app-authorized /validate
                // endpoint, which answers success for EVERY real customer.
                if (innbucksProbePath.contains("/validate")) {
                    log.error("Partner registration innbucks probe-path points at a /validate endpoint "
                            + "({}). That endpoint is authorized by the APP and succeeds for every real "
                            + "InnBucks customer, so it proves a number EXISTS, not that the caller holds "
                            + "it — anyone could register any customer's phone and then spend their "
                            + "points. Point it at an msisdn-scoped endpoint the CUSTOMER's user token "
                            + "authorizes.", innbucksProbePath);
                }
            }
            default -> log.info("Partner registration is enabled in assertion mode (signed proofs only).");
        }
    }
}
