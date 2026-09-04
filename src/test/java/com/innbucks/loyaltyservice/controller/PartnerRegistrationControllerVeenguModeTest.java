package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.client.VeenguIdentityClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.security.RegistrationAssertionVerifier;
import com.innbucks.loyaltyservice.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The {@code veengu} auth mode of {@code POST /loyalty/partner/registrations},
 * at the unit level — same reasoning as the assertion mode's coverage: the
 * enabled paths would need a second Spring context to exercise through MockMvc,
 * which evicts the shared one and slows the whole suite. The disabled posture
 * (404, envelope, permitAll scoping) is pinned end-to-end by
 * {@link PartnerRegistrationControllerSecurityTest}; the wire contract with
 * Veengu by {@code VeenguIdentityClientContractTest}. What belongs HERE is the
 * mode's decision table.
 *
 * <p>The two rows that carry the security weight:
 * <ul>
 *   <li>the phone registered is the one VEENGU names — a body {@code phoneNumber}
 *       is ignored, so a valid session cannot be paired with someone else's
 *       number;</li>
 *   <li>Unavailable maps to a retryable 503 and registers NOTHING — never the
 *       opaque 401 (which would read as "you were refused" to the FE), and
 *       never a default-to-registered.</li>
 * </ul>
 */
class PartnerRegistrationControllerVeenguModeTest {

    private static final String TOKEN = "veengu-session-token";
    private static final String VEENGU_PHONE = "+263771234567";

    private UserService userService;
    private VeenguIdentityClient veenguClient;
    private MemberActivityNotifier notifier;
    private PartnerRegistrationController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        veenguClient = mock(VeenguIdentityClient.class);
        notifier = mock(MemberActivityNotifier.class);
        controller = controller(true);
        when(veenguClient.isConfigured()).thenReturn(true);
    }

    private PartnerRegistrationController controller(boolean enabled) {
        return new PartnerRegistrationController(
                userService, mock(RegistrationAssertionVerifier.class), veenguClient,
                // Wired but unused in veengu mode.
                mock(com.innbucks.loyaltyservice.client.InnbucksSessionClient.class),
                notifier, mock(LoyaltyMetrics.class),
                enabled, "veengu", "");
    }

    @Test
    @DisplayName("verified session registers the phone VEENGU names, source VEENGU_SESSION")
    void verified_registersVeenguPhone() {
        when(veenguClient.identify(TOKEN)).thenReturn(new VeenguIdentityClient.Verified(VEENGU_PHONE));
        when(userService.registerPhone(eq(VEENGU_PHONE), eq(PhoneRegistration.Source.VEENGU_SESSION),
                isNull(), isNull(), isNull()))
                .thenReturn(new UserService.RegistrationResult(true, 2, false));

        ResponseEntity<ApiResult<Map<String, Object>>> response =
                controller.register(null, TOKEN, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData())
                .containsEntry("phoneNumber", VEENGU_PHONE)
                .containsEntry("newlyRegistered", true)
                .containsEntry("projectionsPromoted", 2);
        verify(notifier).notifyPointsUnlocked(VEENGU_PHONE);
    }

    @Test
    @DisplayName("a body phoneNumber is IGNORED — the phone comes only from Veengu's answer")
    void bodyPhone_neverWins() {
        // The attack this pins shut: a customer with a perfectly valid session
        // of their OWN sends someone else's number in the body.
        when(veenguClient.identify(TOKEN)).thenReturn(new VeenguIdentityClient.Verified(VEENGU_PHONE));
        when(userService.registerPhone(anyString(), any(), any(), any(), any()))
                .thenReturn(new UserService.RegistrationResult(false, 0, false));

        var body = new PartnerRegistrationController.PartnerRegistrationRequest(
                null, "+263779999999", "veengu-user-42");
        controller.register(null, TOKEN, null, body);

        verify(userService).registerPhone(eq(VEENGU_PHONE),
                eq(PhoneRegistration.Source.VEENGU_SESSION), eq("veengu-user-42"), isNull(), isNull());
        verify(userService, never()).registerPhone(eq("+263779999999"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a repeat login promotes nothing and does NOT re-text the customer")
    void repeat_doesNotNotify() {
        when(veenguClient.identify(TOKEN)).thenReturn(new VeenguIdentityClient.Verified(VEENGU_PHONE));
        when(userService.registerPhone(anyString(), any(), any(), any(), any()))
                .thenReturn(new UserService.RegistrationResult(false, 0, false));

        controller.register(null, TOKEN, null, null);

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("Rejected (bad/expired token) is the opaque 401 and registers nothing")
    void rejected_isOpaque401() {
        when(veenguClient.identify(TOKEN)).thenReturn(new VeenguIdentityClient.Rejected("http_401"));

        assertThatThrownBy(() -> controller.register(null, TOKEN, null, null))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    assertThat(((LoyaltyException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(((LoyaltyException) e).getCode()).isEqualTo("REGISTRATION_UNAUTHORIZED");
                    // The body must not say WHICH check failed.
                    assertThat(e.getMessage()).doesNotContain("http_401").doesNotContain("Veengu");
                });
        verify(userService, never()).registerPhone(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Unavailable (Veengu unreachable) is a retryable 503, NOT the opaque 401, and registers nothing")
    void unavailable_is503NotUnauthorized() {
        // Fail closed but retryably: no answer from Veengu is not a verdict on
        // the token. A 401 here would make the FE tell the customer they were
        // refused; registering anyway would be a self-service activation path.
        when(veenguClient.identify(TOKEN)).thenReturn(new VeenguIdentityClient.Unavailable("io_error"));

        assertThatThrownBy(() -> controller.register(null, TOKEN, null, null))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    assertThat(((LoyaltyException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((LoyaltyException) e).getCode()).isEqualTo("REGISTRATION_UPSTREAM_UNAVAILABLE");
                });
        verify(userService, never()).registerPhone(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a missing token is refused by the client's blank-token guard as the opaque 401")
    void missingToken_is401() {
        when(veenguClient.identify(null)).thenReturn(new VeenguIdentityClient.Rejected("blank_token"));

        assertThatThrownBy(() -> controller.register(null, null, null, null))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(userService, never()).registerPhone(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("enabled but unconfigured (no base URL / tenant) is the half-provisioned 503")
    void unconfigured_is503() {
        when(veenguClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> controller.register(null, TOKEN, null, null))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    assertThat(((LoyaltyException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((LoyaltyException) e).getCode()).isEqualTo("REGISTRATION_UNCONFIGURED");
                });
        verify(veenguClient, never()).identify(any());
    }

    @Test
    @DisplayName("disabled answers 404 before any Veengu call — same posture as the other modes")
    void disabled_is404() {
        PartnerRegistrationController off = controller(false);

        assertThatThrownBy(() -> off.register(null, TOKEN, null, null))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(userService);
        verify(veenguClient, never()).identify(any());
    }
}
