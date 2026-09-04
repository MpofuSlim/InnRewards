package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.LoyaltySessionIssuer;
import com.innbucks.loyaltyservice.service.LoyaltySessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /loyalty/session/**} — the three calls that keep a customer signed in
 * without a second SMS.
 *
 * <p>Two claims are pinned here that the service tests cannot see:
 * <ul>
 *   <li><b>The exchange takes the phone from the CALLER'S OWN TOKEN and there is
 *       no request field at all</b> ({@link #exchangeUsesTheTokenPhoneOnly()}).
 *       A refresh chain is a long-lived phone-scoped credential; a body phone
 *       would let any holder of one valid session open a chain on someone
 *       else's number.</li>
 *   <li><b>The {@code @PreAuthorize} strings match the scope markers the filter
 *       actually grants</b> ({@link #authorityStringsMatchTheScopeMarkers()}).
 *       They have to be written as literals because the annotation takes a
 *       compile-time constant, so nothing but this test couples them — and a
 *       drift would 403 every customer at the endpoint that exists to stop
 *       them being asked for another SMS.</li>
 * </ul>
 */
class LoyaltySessionControllerTest {

    private static final String PHONE = "+263777224008";

    private LoyaltySessionService sessions;
    private LoyaltySessionController controller;

    @BeforeEach
    void setUp() {
        sessions = mock(LoyaltySessionService.class);
        controller = new LoyaltySessionController(sessions);
        when(sessions.start(anyString(), anyString()))
                .thenReturn(new LoyaltySessionService.Session(PHONE, "access", 43200L, "LRT-new", 7776000L));
        when(sessions.refresh(anyString()))
                .thenReturn(new LoyaltySessionService.Session(PHONE, "access2", 43200L, "LRT-next", 7776000L));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String phone, String... authorities) {
        var auth = new UsernamePasswordAuthenticationToken("customer@test.local", null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new CallerDetails(null, null, phone, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ------------------------------------------------------------- exchange

    @Test
    @DisplayName("SECURITY: the exchange is keyed by the TOKEN's phone — there is no caller-supplied input")
    void exchangeUsesTheTokenPhoneOnly() throws Exception {
        authenticateAs(PHONE, "ROLE_CUSTOMER", LoyaltySessionController.OTP_AUTHORITY);

        controller.exchange();

        verify(sessions).start(eq(PHONE), anyString());
        // The signature is the guarantee: no path variable, no body, no filter.
        assertThat(LoyaltySessionController.class
                .getDeclaredMethod("exchange").getParameterCount()).isZero();
    }

    @Test
    @DisplayName("a token with no phone claim is a 400, not a chain on a null phone")
    void exchangeWithoutAPhoneClaim() {
        authenticateAs(null, "ROLE_CUSTOMER", LoyaltySessionController.OTP_AUTHORITY);

        assertThatThrownBy(() -> controller.exchange())
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    LoyaltyException le = (LoyaltyException) e;
                    assertThat(le.getCode()).isEqualTo("NO_PHONE_CLAIM");
                    assertThat(le.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(sessions, never()).start(anyString(), anyString());
    }

    @Test
    @DisplayName("the chain records WHICH proof channel the session came from")
    void exchangeRecordsTheOriginScope() {
        authenticateAs(PHONE, LoyaltySessionController.OTP_AUTHORITY);
        controller.exchange();
        verify(sessions).start(PHONE, "loyalty-otp");

        SecurityContextHolder.clearContext();
        authenticateAs(PHONE, LoyaltySessionController.SESSION_AUTHORITY);
        controller.exchange();
        verify(sessions).start(PHONE, LoyaltySessionIssuer.LOYALTY_SESSION_SCOPE);
    }

    @Test
    @DisplayName("the exchange response carries everything the app needs to keep going")
    void exchangeResponseShape() {
        authenticateAs(PHONE, LoyaltySessionController.OTP_AUTHORITY);

        Map<String, Object> data = controller.exchange().getBody().getData();

        assertThat(data).containsOnlyKeys("phoneNumber", "loyaltyToken", "expiresInSeconds",
                "refreshToken", "refreshExpiresInSeconds");
        assertThat(data.get("phoneNumber")).isEqualTo(PHONE);
        assertThat(data.get("loyaltyToken")).isEqualTo("access");
        assertThat(data.get("refreshToken")).isEqualTo("LRT-new");
    }

    @Test
    @DisplayName("the @PreAuthorize authority strings match the markers JwtFilter grants")
    void authorityStringsMatchTheScopeMarkers() throws Exception {
        // JwtFilter grants "SERVICE_" + <services entry>.toUpperCase(). Derived
        // the long way here precisely because the annotation cannot.
        assertThat(LoyaltySessionController.SESSION_AUTHORITY)
                .isEqualTo("SERVICE_" + LoyaltySessionIssuer.LOYALTY_SESSION_SCOPE.toUpperCase());

        java.lang.reflect.Field otp = Class
                .forName("com.innbucks.loyaltyservice.security.JwtFilter")
                .getDeclaredField("LOYALTY_OTP_SCOPE");
        otp.setAccessible(true);
        assertThat(LoyaltySessionController.OTP_AUTHORITY)
                .isEqualTo("SERVICE_" + ((String) otp.get(null)).toUpperCase());

        // And the annotation itself names both — a guard that silently dropped
        // one would lock out every customer proved through that channel.
        Method exchange = LoyaltySessionController.class.getDeclaredMethod("exchange");
        String expression = exchange.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class).value();
        assertThat(expression)
                .contains(LoyaltySessionController.OTP_AUTHORITY)
                .contains(LoyaltySessionController.SESSION_AUTHORITY);
    }

    // ------------------------------------------------------ refresh / logout

    @Test
    @DisplayName("refresh passes the presented token through and returns the successor")
    void refreshReturnsTheSuccessor() {
        Map<String, Object> data = controller.refresh(
                new LoyaltySessionController.RefreshRequest("LRT-old")).getBody().getData();

        verify(sessions).refresh("LRT-old");
        assertThat(data.get("refreshToken")).isEqualTo("LRT-next");
        assertThat(data.get("loyaltyToken")).isEqualTo("access2");
        // The phone is a fact the SERVER looked up from the row, never echoed
        // from the request — there is no phone field on this call to echo.
        assertThat(data.get("phoneNumber")).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("refresh needs no Authorization header — nothing reads the security context")
    void refreshDoesNotConsultTheSecurityContext() {
        SecurityContextHolder.clearContext();

        controller.refresh(new LoyaltySessionController.RefreshRequest("LRT-old"));

        verify(sessions).refresh("LRT-old");
    }

    @Test
    @DisplayName("a missing refresh token is a 400, never a lookup on null")
    void missingRefreshToken() {
        for (LoyaltySessionController.RefreshRequest body : new LoyaltySessionController.RefreshRequest[]{
                null,
                new LoyaltySessionController.RefreshRequest(null),
                new LoyaltySessionController.RefreshRequest("  ")}) {
            assertThatThrownBy(() -> controller.refresh(body))
                    .isInstanceOf(LoyaltyException.class)
                    .satisfies(e -> assertThat(((LoyaltyException) e).getCode())
                            .isEqualTo("MISSING_REFRESH_TOKEN"));
            assertThatThrownBy(() -> controller.logout(body))
                    .isInstanceOf(LoyaltyException.class)
                    .satisfies(e -> assertThat(((LoyaltyException) e).getCode())
                            .isEqualTo("MISSING_REFRESH_TOKEN"));
        }
        verify(sessions, never()).refresh(any());
        verify(sessions, never()).signOut(any());
    }

    @Test
    @DisplayName("logout always answers 200 and returns no session material")
    void logoutIsAlways200() {
        var response = controller.logout(new LoyaltySessionController.RefreshRequest("LRT-old"));

        verify(sessions).signOut("LRT-old");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    @DisplayName("the two body-credential paths are the ones SecurityConfig opens, and only those")
    void onlyTheBodyCredentialPathsAreMappedWithoutABearer() {
        // A guard against a fourth endpoint being added here and silently
        // inheriting the permitAll: the exchange is the only method on this
        // controller carrying @PreAuthorize, so any new one must either be
        // annotated or be deliberately added to SecurityConfig.
        List<String> unguarded = java.util.Arrays.stream(
                        LoyaltySessionController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(
                        org.springframework.web.bind.annotation.PostMapping.class))
                .filter(m -> !m.isAnnotationPresent(
                        org.springframework.security.access.prepost.PreAuthorize.class))
                .map(Method::getName)
                .sorted()
                .toList();

        assertThat(unguarded).containsExactly("logout", "refresh");
    }
}
