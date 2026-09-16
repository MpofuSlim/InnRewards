package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Boot-time provisioning check for the InnBucks backlog validate sweep (V44) —
 * the same HALF-PROVISIONED failure the other checks exist to catch: the sweep
 * flag switched on in one config source while the client's credentials live in
 * another that was never updated, producing a cell that looks live and quietly
 * registers nothing, run after run.
 *
 * <p>The endpoint mode ({@code auth-mode=innbucks_validate}) is covered by
 * {@link PartnerRegistrationProvisioningCheck}; this one covers the sweep,
 * which is enabled independently. ERROR, never a boot failure — housekeeping
 * must not stop a cell starting.
 */
@Component
@Slf4j
public class InnbucksValidateProvisioningCheck {

    private final boolean sweepEnabled;
    private final InnbucksCustomerValidateClient validateClient;

    public InnbucksValidateProvisioningCheck(
            @Value("${loyalty.registration.innbucks-validate.sweep.enabled:false}") boolean sweepEnabled,
            InnbucksCustomerValidateClient validateClient) {
        this.sweepEnabled = sweepEnabled;
        this.validateClient = validateClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkSweepProvisioning() {
        if (!sweepEnabled) return;
        if (!validateClient.isConfigured()) {
            log.error("InnBucks backlog validate sweep is HALF-PROVISIONED: "
                    + "LOYALTY_INNBUCKS_VALIDATE_SWEEP_ENABLED is true but the validate client has no "
                    + "credentials — every run will skip and no PENDING customer will be promoted. "
                    + "Provision the BANK_API_URL / BANK_API_KEY / BANK_API_USERNAME / BANK_API_PASSWORD "
                    + "fleet credentials (or the LOYALTY_INNBUCKS_VALIDATE_* overrides) in this host's "
                    + "cell.<iso>.local.env, or set LOYALTY_INNBUCKS_VALIDATE_SWEEP_ENABLED=false.");
            return;
        }
        log.warn("InnBucks backlog validate sweep is ENABLED: each run samples unregistered PENDING "
                + "phones, confirms them against the InnBucks customer directory, and registers the "
                + "hits as spendable (platform-owner eligibility decision — see V44). No customer "
                + "notification is sent from the sweep.");
    }
}
