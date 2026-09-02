package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The boot-time detector for a half-provisioned {@code /loyalty/public/**}
 * surface.
 *
 * <p>Behaviour is expressed through the collaborator, not through log capture:
 * whether the check queries at all is the load-bearing part (a disabled surface
 * must touch nothing, and a pinned one needs no query), and the ERROR text is
 * prose that should be free to change without breaking a test.
 */
class PublicTestProvisioningCheckTest {

    private static final String VALID_UUID = "0a571c1c-7c75-4000-a000-000000000001";

    @Test
    @DisplayName("does nothing at all when the public surface is off — the production case")
    void disabled_doesNotEvenQuery() {
        LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);

        new PublicTestProvisioningCheck(false, "", users).checkPublicTestProvisioning();

        // A production cell has this surface off and must stay off. The check
        // must not add a startup query there, nor log anything that would read
        // as though the surface were live.
        verify(users, never()).countPhonesSpanningTenants();
    }

    @Test
    @DisplayName("a valid pin short-circuits — no query needed, nothing is ambiguous")
    void validPin_doesNotQuery() {
        LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);

        new PublicTestProvisioningCheck(true, VALID_UUID, users).checkPublicTestProvisioning();

        verify(users, never()).countPhonesSpanningTenants();
    }

    @Test
    @DisplayName("a MALFORMED pin is caught without querying — it is a misconfiguration whatever the data says")
    void malformedPin_isReportedWithoutQuerying() {
        // This is the nastiest case in production: PublicTestController's
        // parseUuidOrNull swallows the parse failure and returns null, so a
        // typo'd UUID behaves EXACTLY like an unset one. The operator sees the
        // same AMBIGUOUS_TENANT and has no way to learn the value they set is
        // being ignored. Boot is the only place that can tell them.
        LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);

        new PublicTestProvisioningCheck(true, "not-a-uuid", users).checkPublicTestProvisioning();

        verify(users, never()).countPhonesSpanningTenants();
    }

    @Test
    @DisplayName("no pin: asks the data rather than assuming, because blank is correct on a single-tenant cell")
    void noPin_queriesTheData() {
        LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);
        when(users.countPhonesSpanningTenants()).thenReturn(0L);

        new PublicTestProvisioningCheck(true, "", users).checkPublicTestProvisioning();

        // Must consult the data: a blank pin is documented as correct when the
        // resolution is unambiguous, so warning unconditionally would train the
        // operator to ignore the line.
        verify(users).countPhonesSpanningTenants();
    }

    @Test
    @DisplayName("no pin with phones spanning tenants: the half-provisioned case")
    void noPin_withAffectedPhones_queries() {
        LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);
        when(users.countPhonesSpanningTenants()).thenReturn(42L);

        new PublicTestProvisioningCheck(true, "   ", users).checkPublicTestProvisioning();

        // Whitespace is treated as unset — trimmed before the emptiness test, so
        // a stray space in an env file does not read as a configured pin.
        verify(users).countPhonesSpanningTenants();
    }

    @Test
    @DisplayName("a null pin is treated as unset, not an NPE")
    void nullPin_isTreatedAsUnset() {
        LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);
        when(users.countPhonesSpanningTenants()).thenReturn(1L);

        new PublicTestProvisioningCheck(true, null, users).checkPublicTestProvisioning();

        verify(users).countPhonesSpanningTenants();
    }
}
