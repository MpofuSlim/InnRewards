package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.VoucherGuardProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.exception.GlobalExceptionHandler;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.exception.VoucherCodeGuessException;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.VoucherGuessGuard;
import com.innbucks.loyaltyservice.service.VoucherService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The lockout through the real controller and the real exception handler, with
 * the guard on its in-memory store and a controllable clock. The service is
 * mocked: what it throws is the input here, and which throws COUNT is the
 * contract under test.
 *
 * <p>Statuses are asserted exactly ({@code isTooManyRequests}, {@code isNotFound}) —
 * never {@code is4xxClientError}, which would pass for the wrong 4xx.
 */
class VoucherRedeemLockoutTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final String CUSTOMER_PHONE = "+263771234567";

    private VoucherService service;
    private MutableClock clock;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(VoucherService.class);
        TenantContext tenants = mock(TenantContext.class);
        when(tenants.requireTenantId()).thenReturn(TENANT);
        clock = new MutableClock(Instant.parse("2026-09-25T10:00:00Z"));
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> noRedis = mock(ObjectProvider.class);
        VoucherGuessGuard guard = new VoucherGuessGuard(VoucherGuardProperties.defaults(), noRedis,
                new LoyaltyMetrics(new SimpleMeterRegistry()), clock);
        mvc = MockMvcBuilders.standaloneSetup(new VoucherController(service, tenants, guard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        asCustomer(CUSTOMER_PHONE);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- redeem

    @Test
    void fiveUnknownCodes_thenTheSixthIs429_andTheServiceIsNotCalled() throws Exception {
        when(service.redeem(any(), any(), any())).thenThrow(VoucherCodeGuessException.unknownCode());

        for (int i = 0; i < 5; i++) {
            redeem("7183502649174053").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("404 NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("voucher not found"));
        }
        redeem("7183502649174053")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1800"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("VOUCHER_ATTEMPTS_LOCKED"))
                .andExpect(jsonPath("$.message").value(
                        "Too many incorrect voucher codes were tried. Please wait and try again later."))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(1800));

        verify(service, times(5)).redeem(any(), any(), any());
    }

    @Test
    void aCorrectCodeIsRefusedToo_whileTheLockLasts() throws Exception {
        lockTheCustomerOut();
        reset(service);
        when(service.redeem(any(), any(), any())).thenReturn(
                new Dtos.RedemptionResponse(UUID.randomUUID(), UUID.randomUUID(), "REDEEMED", 0,
                        new BigDecimal("5.00"), Instant.now()));

        redeem("7183502649174053").andExpect(status().isTooManyRequests());
        verify(service, times(0)).redeem(any(), any(), any());
    }

    @Test
    void someoneElsesLiveCode_countsLikeAnUnknownOne() throws Exception {
        when(service.redeem(any(), any(), any())).thenThrow(VoucherCodeGuessException.from(
                LoyaltyException.forbidden("NOT_VOUCHER_OWNER", "This voucher isn't assigned to you.")));

        for (int i = 0; i < 5; i++) {
            redeem("7183502649174053").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NOT_VOUCHER_OWNER"));
        }
        redeem("7183502649174053").andExpect(status().isTooManyRequests());
    }

    @Test
    void honestRefusalsOfRealCodes_andMistypedCodes_neverLock() throws Exception {
        List<Supplier<LoyaltyException>> honest = List.of(
                () -> LoyaltyException.badRequest("VOUCHER_CODE_MISTYPED",
                        "That voucher code doesn't look right. Please check it and try again."),
                () -> LoyaltyException.badRequest("EXPIRED", "This voucher has expired."),
                () -> LoyaltyException.conflict("REVOKED", "This voucher is no longer valid."),
                () -> LoyaltyException.conflict("ALREADY_REDEEMED", "This voucher has already been fully redeemed."),
                () -> LoyaltyException.forbidden("WRONG_MERCHANT", "This voucher can't be redeemed at this shop."),
                () -> LoyaltyException.forbidden("BAD_SIGNATURE", "This voucher couldn't be verified."),
                () -> LoyaltyException.forbidden("USER_PENDING", "still being set up"));
        for (Supplier<LoyaltyException> refusal : honest) {
            reset(service);
            when(service.redeem(any(), any(), any())).thenThrow(refusal.get());
            for (int i = 0; i < 10; i++) {
                redeem("7183502649174053").andExpect(result -> {
                    if (result.getResponse().getStatus() == 429) {
                        throw new AssertionError(refusal.get().getCode() + " must never count toward the lockout");
                    }
                });
            }
        }
    }

    @Test
    void aSuccessDoesNotResetTheCount() throws Exception {
        when(service.redeem(any(), any(), any()))
                .thenThrow(VoucherCodeGuessException.unknownCode())
                .thenThrow(VoucherCodeGuessException.unknownCode())
                .thenThrow(VoucherCodeGuessException.unknownCode())
                .thenThrow(VoucherCodeGuessException.unknownCode())
                .thenReturn(new Dtos.RedemptionResponse(UUID.randomUUID(), UUID.randomUUID(), "REDEEMED", 0,
                        new BigDecimal("5.00"), Instant.now()))
                .thenThrow(VoucherCodeGuessException.unknownCode());

        for (int i = 0; i < 4; i++) {
            redeem("7183502649174053").andExpect(status().isNotFound());
        }
        redeem("7183502649174053").andExpect(status().isOk());
        redeem("7183502649174053").andExpect(status().isNotFound());      // the fifth miss
        redeem("7183502649174053").andExpect(status().isTooManyRequests());
    }

    @Test
    void theLockEndsAfterThirtyMinutes() throws Exception {
        lockTheCustomerOut();
        clock.advance(Duration.ofMinutes(30));
        redeem("7183502649174053").andExpect(status().isNotFound());
    }

    @Test
    void oneCashiersLock_doesNotBlockAnotherTillAtTheSameShop() throws Exception {
        when(service.redeem(any(), any(), any())).thenThrow(VoucherCodeGuessException.unknownCode());
        UUID shop = UUID.randomUUID();

        asStaff(UUID.randomUUID(), shop);
        for (int i = 0; i < 5; i++) {
            redeem("7183502649174053").andExpect(status().isNotFound());
        }
        redeem("7183502649174053").andExpect(status().isTooManyRequests());

        asStaff(UUID.randomUUID(), shop);
        redeem("7183502649174053").andExpect(status().isNotFound());
    }

    @Test
    void theBodyCannotSteerTheKey() throws Exception {
        // userId, deviceFingerprint and ipAddress are caller-chosen; changing
        // them on every attempt must not buy a fresh budget.
        when(service.redeem(any(), any(), any())).thenThrow(VoucherCodeGuessException.unknownCode());
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/loyalty/vouchers/redeem").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"merchantId":"%s","code":"7183502649174053","userId":"%s",
                                     "deviceFingerprint":"dev-%d","ipAddress":"10.0.0.%d"}
                                    """.formatted(MERCHANT, UUID.randomUUID(), i, i)))
                    .andExpect(status().isNotFound());
        }
        redeem("7183502649174053").andExpect(status().isTooManyRequests());
    }

    // ------------------------------------------------------------ markViewed

    @Test
    void markViewed_notTheHoldersCode_countsAndSharesTheLockWithRedeem() throws Exception {
        doThrow(VoucherCodeGuessException.from(LoyaltyException.forbidden("NOT_VOUCHER_OWNER",
                "you can only act on your own vouchers"))).when(service).markViewed(anyString());

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/loyalty/vouchers/codes/7183502649174053/viewed"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/loyalty/vouchers/codes/7183502649174053/viewed"))
                .andExpect(status().isTooManyRequests());
        redeem("7183502649174053").andExpect(status().isTooManyRequests());
    }

    @Test
    void markViewed_unknownCodesAreASilent200_andNeverCount() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/loyalty/vouchers/codes/7183502649174053/viewed"))
                    .andExpect(status().isOk());
        }
    }

    // --------------------------------------------------------------- helpers

    private void lockTheCustomerOut() throws Exception {
        when(service.redeem(any(), any(), any())).thenThrow(VoucherCodeGuessException.unknownCode());
        for (int i = 0; i < 5; i++) {
            redeem("7183502649174053").andExpect(status().isNotFound());
        }
    }

    private ResultActions redeem(String code) throws Exception {
        return mvc.perform(post("/loyalty/vouchers/redeem").contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantId\":\"" + MERCHANT + "\",\"code\":\"" + code + "\"}"));
    }

    private static void asCustomer(String phone) {
        signIn(new CallerDetails(null, null, phone, UUID.randomUUID()), "ROLE_CUSTOMER");
    }

    private static void asStaff(UUID userId, UUID shop) {
        signIn(new CallerDetails(MERCHANT, shop, null, userId), "ROLE_SHOP_USER");
    }

    private static void signIn(CallerDetails details, String... roles) {
        var auth = new UsernamePasswordAuthenticationToken("caller", null,
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
