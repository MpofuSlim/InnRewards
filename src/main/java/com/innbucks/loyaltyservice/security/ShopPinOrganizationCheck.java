package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.repository.MerchantRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Decides whether a token that carries BOTH shop-staff scope (a shop role, a
 * {@code merchantId} or a {@code shopId}) and a loyalty organization may wear
 * the organization's merchant-admin hat ({@link JwtFilter}).
 *
 * <p>user-service mints the {@code merchantId}/{@code shopId} claims for any
 * holder of a shop role, and the organization claims for whichever organization
 * the session acts for, independently. So a cashier whom ANY loyalty business
 * adds as OWNER or ADMIN arrived here as a merchant admin pinned to their
 * EMPLOYER: {@link MerchantAuthz} checks the pin first, for any role, so the
 * organization's grant was spent on a merchant it does not own — free vouchers,
 * shop and merchant writes, the tenant's global rule, unblocks.
 *
 * <p>The one legitimate shape is a shop admin or cashier the employer promoted
 * in its OWN organization: that organization owns the pinned merchant, so the
 * grant and the pin agree. That is the only case answered {@code true}. Every
 * other answer — another organization's merchant, no pin, an unknown merchant,
 * or a lookup that failed — is {@code false}: the token keeps its shop hat and
 * loses the organization's. It never throws, because {@link JwtFilter} answers
 * any exception {@code 401 INVALID_TOKEN}, which would sign a cashier out of
 * the till over a database blip.
 *
 * <p>Deliberately uncached, like {@code TenantCachedLookup.organizationOwnsMerchantIn}:
 * an operator can still stamp or correct {@code merchants.organization_id} (the
 * V51 remap of pre-organization rows), and a stale "owns" would keep the old
 * organization's hat on the pin. It runs only for two-hat tokens and is a
 * primary-key probe.
 */
@Component
@Slf4j
public class ShopPinOrganizationCheck {

    static final String METRIC = "loyalty.auth.two_hat";

    private final MerchantRepository merchants;
    private final Counter granted;
    private final Counter withheld;
    private final Counter lookupFailed;

    public ShopPinOrganizationCheck(MerchantRepository merchants, MeterRegistry registry) {
        this.merchants = merchants;
        // Registered at 0 so increase() sees the first event.
        this.granted = counter(registry, "org_owns_pin");
        this.withheld = counter(registry, "withheld");
        this.lookupFailed = counter(registry, "lookup_failed");
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder(METRIC)
                .description("Requests whose token carries shop scope (role, merchantId or shopId) AND a "
                        + "loyalty organization, by whether the organization's hat was worn")
                .tag("outcome", outcome)
                .register(registry);
    }

    /**
     * {@code true} only when {@code organizationId} owns {@code pinnedMerchantId}.
     * Counts every decision.
     */
    public boolean organizationOwnsPin(UUID organizationId, UUID pinnedMerchantId) {
        if (organizationId == null || pinnedMerchantId == null) {
            withheld.increment();
            return false;
        }
        try {
            boolean owns = merchants.findOrganizationIdById(pinnedMerchantId)
                    .map(organizationId::equals)
                    .orElse(false);
            (owns ? granted : withheld).increment();
            return owns;
        } catch (RuntimeException e) {
            lookupFailed.increment();
            // No ids: one line per request is enough to see the failure.
            log.warn("Shop-pin organization lookup failed; the token keeps its shop role only: {}",
                    e.getClass().getSimpleName());
            return false;
        }
    }
}
