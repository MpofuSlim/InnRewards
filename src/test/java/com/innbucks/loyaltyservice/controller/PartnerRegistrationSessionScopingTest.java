package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.client.InnbucksSessionClient;
import com.innbucks.loyaltyservice.client.VeenguIdentityClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.security.LoyaltySessionIssuer;
import com.innbucks.loyaltyservice.security.RegistrationAssertionVerifier;
import com.innbucks.loyaltyservice.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WHO gets a loyalty session back from a registration — the one rule that spans
 * every auth mode, so it is pinned in one place rather than scattered across
 * each mode's own test.
 *
 * <h2>The distinction</h2>
 * <ul>
 *   <li>{@code innbucks} / {@code veengu} — the caller IS the customer's own
 *       device, proving a phone with a session it already holds. Continuing that
 *       into a loyalty session is the whole point: without it, a customer who
 *       just proved their phone would still need an SMS before they could
 *       spend.</li>
 *   <li>{@code assertion} / {@code key} — the caller is a partner's BACKEND,
 *       proving a phone on someone else's behalf. Handing it a live customer
 *       session would let it act AS every customer it registers. Registering
 *       for someone is a legitimate partner power; becoming them is not.</li>
 * </ul>
 *
 * <p>These cases are the regression guard on that second bullet. Widening the
 * session to a partner mode would be a silent, invisible privilege escalation:
 * nothing errors, the partner simply starts receiving tokens that let it spend
 * other people's points.
 */
class PartnerRegistrationSessionScopingTest {

    private static final String PHONE = "+263777224008";
    private static final String PARTNER_KEY = "the-shared-partner-key";

    private final UserService userService = mock(UserService.class);
    private final LoyaltySessionIssuer sessionIssuer = mock(LoyaltySessionIssuer.class);
    private final InnbucksSessionClient innbucksClient = mock(InnbucksSessionClient.class);

    private PartnerRegistrationController controller(String mode, String key) {
        when(sessionIssuer.issue(anyString())).thenReturn("minted.session.token");
        when(sessionIssuer.ttlSeconds()).thenReturn(43200L);
        when(userService.normalizePhone(anyString())).thenReturn(PHONE);
        when(userService.registerPhone(anyString(), any(), any(), any(), any()))
                .thenReturn(new UserService.RegistrationResult(true, 1, false));
        when(innbucksClient.isConfigured()).thenReturn(true);
        when(innbucksClient.verifyOwnership(anyString(), anyString()))
                .thenReturn(new InnbucksSessionClient.Verified("000"));
        return new PartnerRegistrationController(
                userService, mock(RegistrationAssertionVerifier.class),
                mock(VeenguIdentityClient.class), innbucksClient, sessionIssuer,
                mock(MemberActivityNotifier.class), mock(LoyaltyMetrics.class),
                true, mode, key);
    }

    private static PartnerRegistrationController.PartnerRegistrationRequest body() {
        return new PartnerRegistrationController.PartnerRegistrationRequest(null, PHONE, "ext-1");
    }

    @Test
    @DisplayName("SECURITY: `key` mode — a partner backend registers the phone but gets NO session")
    void keyMode_withholdsTheSession() {
        var response = controller("key", PARTNER_KEY).register(PARTNER_KEY, null, null, body());

        // The registration itself succeeded — that is the partner's legitimate power.
        assertThat(response.getBody().getData())
                .containsEntry("registered", true)
                .containsEntry("phoneNumber", PHONE);
        // But no session: this caller must not be able to ACT as the customer.
        assertThat(response.getBody().getData())
                .doesNotContainKey("loyaltyToken")
                .doesNotContainKey("expiresInSeconds");
        verify(sessionIssuer, never()).issue(any());
    }

    @Test
    @DisplayName("SECURITY: the `key` response is unchanged from before sessions existed")
    void keyMode_responseShapeIsUnchanged() {
        // A partner built against the previous shape must see exactly what it
        // saw before — adding a field here would also be handing them a
        // credential they never asked for.
        var data = controller("key", PARTNER_KEY).register(PARTNER_KEY, null, null, body())
                .getBody().getData();

        assertThat(data.keySet()).containsExactlyInAnyOrder(
                "phoneNumber", "registered", "newlyRegistered", "projectionsPromoted", "replay");
    }

    @Test
    @DisplayName("`innbucks` mode — the customer's own device DOES get a session")
    void innbucksMode_issuesTheSession() {
        var response = controller("innbucks", "").register(null, null, "user-token", body());

        assertThat(response.getBody().getData())
                .containsEntry("loyaltyToken", "minted.session.token")
                .containsEntry("expiresInSeconds", 43200L);
        verify(sessionIssuer).issue(PHONE);
    }

    @Test
    @DisplayName("the phone registered and the phone the session is scoped to are the SAME value")
    void sessionIsScopedToTheRegisteredPhone() {
        // A session scoped to a different spelling than the one registered would
        // authenticate a caller who then matches no account downstream.
        controller("innbucks", "").register(null, null, "user-token", body());

        verify(userService).registerPhone(org.mockito.ArgumentMatchers.eq(PHONE),
                org.mockito.ArgumentMatchers.eq(PhoneRegistration.Source.INNBUCKS_SESSION),
                any(), any(), any());
        verify(sessionIssuer).issue(PHONE);
    }
}
