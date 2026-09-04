package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.client.InnbucksSessionClient;
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
 * The {@code innbucks} auth mode of {@code POST /loyalty/partner/registrations}
 * at the unit level — the wire contract with the middleware is pinned by
 * {@code InnbucksSessionClientContractTest}, the disabled posture end-to-end by
 * {@link PartnerRegistrationControllerSecurityTest}. What belongs HERE is the
 * mode's decision table.
 *
 * <p>The rows that carry the security weight:
 * <ul>
 *   <li>the claimed phone is registered ONLY after the probe verifies it, and
 *       it is the NORMALISED spelling that is both proved and stored — proving
 *       one spelling and registering another would register something
 *       unproven;</li>
 *   <li>a refusal registers nothing and never leaks which check failed;</li>
 *   <li>Unavailable is a retryable 503, never the opaque 401 and never a
 *       registration — no answer from the middleware is not a verdict.</li>
 * </ul>
 */
class PartnerRegistrationControllerInnbucksModeTest {

    private static final String USER_TOKEN = "customer-user-token";
    private static final String RAW_PHONE = "0777224008";
    private static final String E164 = "+263777224008";

    private UserService userService;
    private InnbucksSessionClient innbucksClient;
    private MemberActivityNotifier notifier;
    private PartnerRegistrationController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        innbucksClient = mock(InnbucksSessionClient.class);
        notifier = mock(MemberActivityNotifier.class);
        controller = controller(true);
        when(innbucksClient.isConfigured()).thenReturn(true);
        // The service canonicalises; the controller must use that value for BOTH
        // the probe and the registration.
        when(userService.normalizePhone(RAW_PHONE)).thenReturn(E164);
        when(userService.normalizePhone(E164)).thenReturn(E164);
    }

    private PartnerRegistrationController controller(boolean enabled) {
        return new PartnerRegistrationController(
                userService, mock(RegistrationAssertionVerifier.class), innbucksClient,
                notifier, mock(LoyaltyMetrics.class),
                enabled, "innbucks", "");
    }

    private static PartnerRegistrationController.PartnerRegistrationRequest body(String phone) {
        return new PartnerRegistrationController.PartnerRegistrationRequest(null, phone, "innbucks-42");
    }

    @Test
    @DisplayName("a proved claim registers the phone with source INNBUCKS_SESSION")
    void verified_registers() {
        when(innbucksClient.verifyOwnership(USER_TOKEN, E164))
                .thenReturn(new InnbucksSessionClient.Verified("000"));
        when(userService.registerPhone(eq(E164), eq(PhoneRegistration.Source.INNBUCKS_SESSION),
                eq("innbucks-42"), isNull(), isNull()))
                .thenReturn(new UserService.RegistrationResult(true, 2, false));

        ResponseEntity<ApiResult<Map<String, Object>>> response =
                controller.register(null, USER_TOKEN, body(RAW_PHONE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData())
                .containsEntry("phoneNumber", E164)
                .containsEntry("newlyRegistered", true)
                .containsEntry("projectionsPromoted", 2);
        verify(notifier).notifyPointsUnlocked(E164);
    }

    @Test
    @DisplayName("the NORMALISED phone is what gets probed — not the raw body value")
    void probesTheNormalisedPhone() {
        // Proving "0777224008" while registering "+263777224008" would mean the
        // stored registration was never actually the thing verified.
        when(innbucksClient.verifyOwnership(anyString(), anyString()))
                .thenReturn(new InnbucksSessionClient.Verified("000"));
        when(userService.registerPhone(anyString(), any(), any(), any(), any()))
                .thenReturn(new UserService.RegistrationResult(true, 1, false));

        controller.register(null, USER_TOKEN, body(RAW_PHONE));

        verify(innbucksClient).verifyOwnership(USER_TOKEN, E164);
        verify(innbucksClient, never()).verifyOwnership(USER_TOKEN, RAW_PHONE);
        verify(userService).registerPhone(eq(E164), any(), any(), any(), any());
    }

    @Test
    @DisplayName("SECURITY: a refused claim registers nothing and answers the opaque 401")
    void rejected_registersNothing() {
        // The cross-customer case: a caller with a valid session of their own
        // naming someone else's number.
        when(innbucksClient.verifyOwnership(USER_TOKEN, E164))
                .thenReturn(new InnbucksSessionClient.Rejected("code_02"));

        assertThatThrownBy(() -> controller.register(null, USER_TOKEN, body(RAW_PHONE)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    assertThat(((LoyaltyException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(((LoyaltyException) e).getCode()).isEqualTo("REGISTRATION_UNAUTHORIZED");
                    // Must not leak whether the number exists or which check failed.
                    assertThat(e.getMessage()).doesNotContain("code_02").doesNotContain("263777224008");
                });
        verify(userService, never()).registerPhone(any(), any(), any(), any(), any());
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("Unavailable is a retryable 503, NOT the opaque 401, and registers nothing")
    void unavailable_is503() {
        // Fail closed but retryably: the middleware being unreachable says
        // nothing about the claim, so the FE must not tell the customer they
        // were refused — and nothing may be registered on no answer.
        when(innbucksClient.verifyOwnership(USER_TOKEN, E164))
                .thenReturn(new InnbucksSessionClient.Unavailable("io_error"));

        assertThatThrownBy(() -> controller.register(null, USER_TOKEN, body(RAW_PHONE)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    assertThat(((LoyaltyException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((LoyaltyException) e).getCode())
                            .isEqualTo("REGISTRATION_UPSTREAM_UNAVAILABLE");
                });
        verify(userService, never()).registerPhone(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a missing user token is refused by the client's guard, registering nothing")
    void missingToken_is401() {
        when(innbucksClient.verifyOwnership(isNull(), eq(E164)))
                .thenReturn(new InnbucksSessionClient.Rejected("blank_token"));

        assertThatThrownBy(() -> controller.register(null, null, body(RAW_PHONE)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(userService, never()).registerPhone(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a missing phone is a 400 and never probes")
    void missingPhone_is400() {
        assertThatThrownBy(() -> controller.register(null, USER_TOKEN, body(null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    assertThat(((LoyaltyException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((LoyaltyException) e).getCode()).isEqualTo("BAD_PHONE");
                });
        verify(innbucksClient, never()).verifyOwnership(any(), any());
    }

    @Test
    @DisplayName("an entirely missing body is a 400, not an NPE")
    void missingBody_is400() {
        assertThatThrownBy(() -> controller.register(null, USER_TOKEN, null))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(innbucksClient, never()).verifyOwnership(any(), any());
    }

    @Test
    @DisplayName("a repeat registration promotes nothing and does NOT re-notify the customer")
    void repeat_doesNotNotify() {
        // Safe to call on every login: the endpoint is expected to be hit each
        // time the app starts, and a customer must not be texted every time.
        when(innbucksClient.verifyOwnership(USER_TOKEN, E164))
                .thenReturn(new InnbucksSessionClient.Verified("000"));
        when(userService.registerPhone(anyString(), any(), any(), any(), any()))
                .thenReturn(new UserService.RegistrationResult(false, 0, false));

        controller.register(null, USER_TOKEN, body(RAW_PHONE));

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("enabled but unconfigured is the half-provisioned 503, and never probes")
    void unconfigured_is503() {
        when(innbucksClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> controller.register(null, USER_TOKEN, body(RAW_PHONE)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    assertThat(((LoyaltyException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((LoyaltyException) e).getCode()).isEqualTo("REGISTRATION_UNCONFIGURED");
                });
        verify(innbucksClient, never()).verifyOwnership(any(), any());
    }

    @Test
    @DisplayName("disabled answers 404 before any middleware call")
    void disabled_is404() {
        PartnerRegistrationController off = controller(false);

        assertThatThrownBy(() -> off.register(null, USER_TOKEN, body(RAW_PHONE)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(userService);
        verify(innbucksClient, never()).verifyOwnership(any(), any());
    }

    @Test
    @DisplayName("a partner key grants nothing in this mode")
    void partnerKeyHeader_isIgnored() {
        // The modes are exclusive: presenting a key must not skip the probe.
        when(innbucksClient.verifyOwnership(USER_TOKEN, E164))
                .thenReturn(new InnbucksSessionClient.Rejected("code_02"));

        assertThatThrownBy(() -> controller.register("some-partner-key", USER_TOKEN, body(RAW_PHONE)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(innbucksClient).verifyOwnership(USER_TOKEN, E164);
    }
}
