package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Boot-time half-provisioned detector for the {@code /loyalty/public/**} test
 * surface — the same ZimSwitch lesson {@link FxProvisioningCheck} applies to FX,
 * applied to the tenant pin.
 *
 * <p>The failure this exists to surface: the switch that turns the public
 * surface ON ({@code LOYALTY_PUBLIC_TEST_ENABLED}) and the pin that makes its
 * points WRITES usable ({@code LOYALTY_PUBLIC_TEST_TENANT_ID}) are separate
 * keys, and only the first was ever shipped to the ZW cell. The result looks
 * healthy — reads work, balances render, vouchers arrive — while
 * {@code POST /loyalty/public/customers/{phone}/points/send} and
 * {@code …/points/redeem} refuse every customer whose phone spans more than one
 * tenant with {@code AMBIGUOUS_TENANT}. Nothing announced that; it was found one
 * 400 at a time from a client log.
 *
 * <p>Two distinct conditions are reported, because they need different fixes:
 *
 * <ol>
 *   <li><b>A malformed pin</b> is always a misconfiguration.
 *       {@code PublicTestController.parseUuidOrNull} swallows the parse failure
 *       and returns null, so a typo'd UUID behaves <em>identically</em> to an
 *       unset one — the operator sees the same error and has no way to tell that
 *       the value they set is being ignored. Logged as ERROR.</li>
 *   <li><b>No pin on a cell where phones actually span tenants.</b> A blank pin
 *       is perfectly correct on a single-tenant cell — the YAML says so, and the
 *       endpoints resolve automatically when unambiguous. It is only wrong when
 *       the data makes it ambiguous, so the check asks the database rather than
 *       assuming, and reports the exact number of customers currently refused.
 *       Logged as ERROR with that count; silent when the count is zero.</li>
 * </ol>
 *
 * <p>Note that a phone spanning tenants is normal, not corruption: a
 * {@code LoyaltyUser} is a per-tenant projection, and buying an event ticket
 * mints one under the seeded ticketing tenant. Any customer who has both bought
 * a ticket and transacted with a loyalty merchant has two — the ordinary
 * super-app case.
 *
 * <p>ERROR, never a boot failure: the public surface is a test affordance, the
 * request-time refusal is already the hard guard, and a cell must not fail to
 * start over a misconfigured optional pin. Runs only when the surface is
 * enabled, so a production cell (where it is off, and must stay off) logs
 * nothing and does no query.
 */
@Component
@Slf4j
public class PublicTestProvisioningCheck {

    private final boolean enabled;
    private final String configuredTenantId;
    private final LoyaltyUserRepository users;

    public PublicTestProvisioningCheck(
            @Value("${loyalty.public.test.enabled:false}") boolean enabled,
            @Value("${loyalty.public.test.tenant-id:}") String configuredTenantId,
            LoyaltyUserRepository users) {
        this.enabled = enabled;
        this.configuredTenantId = configuredTenantId;
        this.users = users;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkPublicTestProvisioning() {
        if (!enabled) return;

        String raw = configuredTenantId == null ? "" : configuredTenantId.trim();

        if (!raw.isEmpty()) {
            try {
                UUID.fromString(raw);
                log.info("Public test surface is enabled and pinned to tenant {}.", raw);
            } catch (IllegalArgumentException ex) {
                log.error("Public test surface is HALF-PROVISIONED: LOYALTY_PUBLIC_TEST_TENANT_ID "
                        + "is set but is not a valid UUID, so it is SILENTLY IGNORED and the points "
                        + "writes behave exactly as if no pin were configured (AMBIGUOUS_TENANT for "
                        + "any phone spanning tenants). Fix the value or clear it.");
            }
            return;
        }

        // No pin. Correct on a single-tenant cell; broken the moment a phone has
        // more than one projection — so ask, rather than warn on every cell.
        long affected = users.countPhonesSpanningTenants();
        if (affected > 0) {
            log.error("Public test surface is HALF-PROVISIONED: {} customer phone(s) hold a loyalty "
                            + "projection under more than one tenant, and no LOYALTY_PUBLIC_TEST_TENANT_ID "
                            + "is set. Every POST /loyalty/public/customers/{{phone}}/points/send and "
                            + "…/points/redeem for those customers is refused with AMBIGUOUS_TENANT. "
                            + "Reads are unaffected, so this surface looks healthy. Set the pin, or "
                            + "have the caller supply the tenant per request.",
                    affected);
        } else {
            log.info("Public test surface is enabled with no tenant pin; no phone currently spans "
                    + "tenants, so the points writes resolve unambiguously.");
        }
    }
}
