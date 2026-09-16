package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient;
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
 * The {@code innbucks_validate} auth mode of
 * {@code POST /loyalty/partner/registrations} at the unit level — the wire
 * contract with the platform is pinned by
 * {@code InnbucksCustomerValidateClientContractTest}, the no-session property
 * by {@link PartnerRegistrationSessionScopingTest}. What belongs HERE is the
 * mode's decision table.
 *
 * <p>This mode is an ELIGIBILITY check (platform-owner decision: every InnBucks
 * customer may spend points), so unlike the dead {@code innbucks} mode there is
 * no ownership claim to defend. The rows that still carry weight:
 * <ul>
 *   <li>the phone is registered ONLY after the directory confirms it, in the
 *       NORMALISED spelling that was checked;</li>
 *   <li>a refusal registers nothing and the opaque 401 hides WHICH check failed
 *       (not-customer vs a config/upstream fault, which is a 503) — it does NOT
 *       hide customer-existence, which 200-vs-401 inherently reveals and which
 *       this eligibility mode exists to answer;</li>
 *   <li>Unavailable (including OUR credentials being refused) is a retryable
 *       503, never the opaque 401 and never a registration.</li>
 * </ul>
 */
class PartnerRegistrationControllerInnbucksValidateModeTest {

    private static final String RAW_PHONE = "0782606983";
    private static final String E164 = "+263782606983";

    private UserService userService;
    private InnbucksCustomerValidateClient validateClient;
    private MemberActivityNotifier notifier;
    private com.innbucks.loyaltyservice.security.LoyaltySessionIssuer sessionIssuer;
    private PartnerRegistrationController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        validateClient = mock(InnbucksCustomerValidateClient.class);
        notifier = mock(MemberActivityNotifier.class);
        sessionIssuer = mock(com.innbucks.loyaltyservice.security.LoyaltySessionIssuer.class);
        controller = controller(true);
        when(validateClient.isConfigured()).thenReturn(true);
        // The service canonicalises; the controller must use that value for
        // BOTH the directory check and the registration.
        when(userService.normalizePhone(RAW_PHONE)).thenReturn(E164);
        when(userService.normalizePhone(E164)).thenReturn(E164);
    }

    private PartnerRegistrationController controller(boolean enabled) {
        return new PartnerRegistrationController(
                userService, mock(RegistrationAssertionVerifier.class),
                mock(com.innbucks.loyaltyservice.client.VeenguIdentityClient.class),
                // The dead session client is wired but must never be touched in
                // this mode — a strict mock would fail the moment it were.
                mock(com.innbucks.loyaltyservice.client.InnbucksSessionClient.class),
                validateClient, sessionIssuer, notifier, mock(LoyaltyMetrics.class),
                enabled, "innbucks_validate", "");
    }

    private static PartnerRegistrationController.PartnerRegistrationRequest body(String phone) {
        return new PartnerRegistrationController.PartnerRegistrationRequest(null, phone, "app-login-77");
    }

    @Test
    @DisplayName("a confirmed customer registers the NORMALISED phone with source INNBUCKS_VALIDATE")
    void customer_registers() {
        when(validateClient.checkCustomer(E164))
                .thenReturn(new InnbucksCustomerValidateClient.Customer("00"));
        when(userService.registerPhone(eq(E164), eq(PhoneRegistration.Source.INNBUCKS_VALIDATE),
                eq("app-login-77"), isNull(), isNull()))
                .thenReturn(new UserService.RegistrationResult(true, 2, false));

        ResponseEntity<ApiResult<Map<String, Object>>> response =
                controller.register(null, null, null, body(RAW_PHONE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData())
                .containsEntry("phoneNumber", E164)
                .containsEntry("registered", true)
                .containsEntry("newlyRegistered", true)
                .containsEntry("projectionsPromoted", 2)
                // Eligibility, not identity: NO session, ever.
                .doesNotContainKey("loyaltyToken");
        verify(validateClient).checkCustomer(E164);
        verify(notifier).notifyPointsUnlocked(E164);
        verifyNoInteractions(sessionIssuer);
    }

    @Test
    @DisplayName("a repeat registration is a quiet no-op — the customer is not re-texted per login")
    void repeat_doesNotRenotify() {
        when(validateClient.checkCustomer(E164))
                .thenReturn(new InnbucksCustomerValidateClient.Customer("00"));
        when(userService.registerPhone(any(), any(), any(), any(), any()))
                .thenReturn(new UserService.RegistrationResult(false, 0, false));

        var response = controller.register(null, null, null, body(RAW_PHONE));

        assertThat(response.getBody().getData())
                .containsEntry("newlyRegistered", false)
                .containsEntry("projectionsPromoted", 0);
        verify(notifier, never()).notifyPointsUnlocked(anyString());
    }

    @Test
    @DisplayName("SECURITY: not a customer -> opaque 401, and nothing is registered")
    void notACustomer_isOpaque401() {
        when(validateClient.checkCustomer(E164))
                .thenReturn(new InnbucksCustomerValidateClient.NotACustomer("code_06"));

        assertThatThrownBy(() -> controller.register(null, null, null, body(RAW_PHONE)))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(401);
                    assertThat(ex.getCode()).isEqualTo("REGISTRATION_UNAUTHORIZED");
                    // Opaque: the body must not say the number is not a customer.
                    assertThat(ex.getMessage()).doesNotContainIgnoringCase("customer");
                });
        verify(userService, never()).registerPhone(any(), any(), any(), any(), any());
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("upstream unavailable -> retryable 503, never a registration and never the opaque 401")
    void unavailable_is503() {
        when(validateClient.checkCustomer(E164))
                .thenReturn(new InnbucksCustomerValidateClient.Unavailable("credentials_rejected"));

        assertThatThrownBy(() -> controller.register(null, null, null, body(RAW_PHONE)))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(503);
                    assertThat(ex.getCode()).isEqualTo("REGISTRATION_UPSTREAM_UNAVAILABLE");
                });
        verify(userService, never()).registerPhone(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("unconfigured client -> 503 REGISTRATION_UNCONFIGURED before any phone is read")
    void unconfigured_is503() {
        when(validateClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> controller.register(null, null, null, body(RAW_PHONE)))
                .isInstanceOfSatisfying(LoyaltyException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("REGISTRATION_UNCONFIGURED"));
        verify(validateClient, never()).checkCustomer(anyString());
    }

    @Test
    @DisplayName("a missing phone is 400 BAD_PHONE and the directory is never asked")
    void missingPhone_is400() {
        assertThatThrownBy(() -> controller.register(null, null, null, body("  ")))
                .isInstanceOfSatisfying(LoyaltyException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("BAD_PHONE"));
        assertThatThrownBy(() -> controller.register(null, null, null, null))
                .isInstanceOfSatisfying(LoyaltyException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("BAD_PHONE"));
        verify(validateClient, never()).checkCustomer(anyString());
    }

    @Test
    @DisplayName("disabled answers 404 — indistinguishable from no such route")
    void disabled_is404() {
        assertThatThrownBy(() -> controller(false).register(null, null, null, body(RAW_PHONE)))
                .isInstanceOfSatisfying(LoyaltyException.class, ex ->
                        assertThat(ex.getStatus().value()).isEqualTo(404));
        verifyNoInteractions(validateClient);
    }
}
