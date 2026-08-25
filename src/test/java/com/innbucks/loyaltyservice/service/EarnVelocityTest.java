package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.EarnChannel;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rolling per-operator ceiling on staff-typed earns.
 *
 * <p>The gap this closes: every pre-existing earn guard is an IDENTITY check —
 * SELF_EARN, STAFF_RECIPIENT, REFERENCE_REQUIRED. A cashier posting hundreds of
 * small earns to hundreds of different customer phones, each with a plausible
 * receipt reference, trips none of them, because each individual request is
 * legitimate. Only the rate gives it away.
 *
 * <p>It is also invisible to the existing {@link FraudService} velocity
 * auto-block, which counts rows in {@code fraud_attempts} — a flood of
 * successful earns produces none.
 */
class EarnVelocityTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID OPERATOR = UUID.randomUUID();

    private static final int MAX = 60;
    private static final int WINDOW = 3600;

    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final UserService users = mock(UserService.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final WalletService walletService = mock(WalletService.class);
    private final RulesEngine rulesEngine = mock(RulesEngine.class);
    private final FraudService fraud = mock(FraudService.class);
    private final StaffRegistry staffRegistry = mock(StaffRegistry.class);
    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = newService(new LoyaltyProperties.Earn(false, false, false, 300, MAX, WINDOW));

        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setCurrency("USD");
        m.setStatus(Merchant.Status.ACTIVE);
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(m);

        LoyaltyUser u = new LoyaltyUser();
        u.setId(RECIPIENT);
        u.setPhoneNumber("+263771234567");
        u.setStatus(LoyaltyUser.Status.ACTIVE);
        when(users.require(eq(TENANT), any())).thenReturn(u);
        when(users.findOrCreatePending(any(), any(), any())).thenReturn(u);

        when(rulesEngine.evaluate(any(), any(), any(), any()))
                .thenReturn(new RulesEngine.Evaluation(new BigDecimal("10"), null, null, null));

        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        when(walletService.mainWallet(any())).thenReturn(w);
        when(walletService.apply(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("100"));
        when(transactions.findFirstByMerchantIdAndReference(any(), any())).thenReturn(Optional.empty());
        when(transactions.countTypedEarnsByOperatorSince(any(), any())).thenReturn(0L);

        authenticateAs("ROLE_SHOP_USER");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- the ceiling ----

    @Test
    void anEarnBelowTheCeiling_isAllowed() {
        when(transactions.countTypedEarnsByOperatorSince(eq(OPERATOR), any())).thenReturn(59L);

        service.post(TENANT, MERCHANT, request(), null, EarnChannel.TYPED_PHONE);

        verify(transactions).saveAndFlush(any());
    }

    @Test
    void theEarnThatWouldBeTheSixtyFirst_isRefused() {
        // 60 already posted means this request is the 61st — over a ceiling of 60.
        when(transactions.countTypedEarnsByOperatorSince(eq(OPERATOR), any())).thenReturn(60L);

        assertThatThrownBy(() -> service.post(TENANT, MERCHANT, request(), null, EarnChannel.TYPED_PHONE))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("too many point awards");

        verify(transactions, never()).saveAndFlush(any());
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void aFloodOfIndividuallyLegitimateEarns_isWhatThisCatches() {
        // Every identity guard is ON here and the request satisfies all of them:
        // the recipient is not the caller, is not staff, and the earn cites a
        // reference. Pre-velocity this was completely undetectable.
        TransactionService allGuardsOn =
                newService(new LoyaltyProperties.Earn(true, true, true, 300, MAX, WINDOW));
        when(staffRegistry.isStaffPhone(any(), any())).thenReturn(false);
        when(transactions.countTypedEarnsByOperatorSince(eq(OPERATOR), any())).thenReturn(500L);

        assertThatThrownBy(() -> allGuardsOn.post(TENANT, MERCHANT, request(), null, EarnChannel.TYPED_PHONE))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("too many point awards");
    }

    // ---- channel scoping ----

    @Test
    void checkoutS2SIsNotThrottled_evenWellOverTheCeiling() {
        // Server-side ticket sales. Throttling a legitimate burst here would be
        // an outage, not a control — a busy on-sale is exactly the shape a
        // naive rate limit would kill.
        when(transactions.countTypedEarnsByOperatorSince(any(), any())).thenReturn(100_000L);

        service.post(TENANT, MERCHANT, request(), null, EarnChannel.CHECKOUT_S2S);

        verify(transactions).saveAndFlush(any());
        verify(transactions, never()).countTypedEarnsByOperatorSince(any(), any());
    }

    @Test
    void qrPresenceIsNotThrottled() {
        // QR consume credits the authenticated scanner: caller == recipient is
        // the design, not the abuse.
        when(transactions.countTypedEarnsByOperatorSince(any(), any())).thenReturn(100_000L);

        service.post(TENANT, MERCHANT, request(), null, EarnChannel.QR_PRESENCE);

        verify(transactions).saveAndFlush(any());
        verify(transactions, never()).countTypedEarnsByOperatorSince(any(), any());
    }

    // ---- exemptions, escape hatches, failure posture ----

    @Test
    void superAdminIsExempt() {
        authenticateAs("ROLE_SUPER_ADMIN");
        when(transactions.countTypedEarnsByOperatorSince(any(), any())).thenReturn(100_000L);

        service.post(TENANT, MERCHANT, request(), null, EarnChannel.TYPED_PHONE);

        verify(transactions).saveAndFlush(any());
        verify(transactions, never()).countTypedEarnsByOperatorSince(any(), any());
    }

    @Test
    void aTokenWithNoOperatorId_failsOPEN() {
        // A legacy token without the userId claim can't be attributed to a
        // person, so the count is meaningless. Failing CLOSED here would take
        // the till down on a token-shape regression — a worse outcome than the
        // gap, and the same posture the staff-recipient guard takes when
        // user-service is unreachable.
        var auth = new UsernamePasswordAuthenticationToken(
                "legacy@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_SHOP_USER")));
        auth.setDetails(new CallerDetails(null, null, "+263779999999", null));
        SecurityContextHolder.getContext().setAuthentication(auth);

        service.post(TENANT, MERCHANT, request(), null, EarnChannel.TYPED_PHONE);

        verify(transactions).saveAndFlush(any());
    }

    @Test
    void aZeroCeilingDisablesTheCheck() {
        TransactionService off =
                newService(new LoyaltyProperties.Earn(false, false, false, 300, 0, WINDOW));
        when(transactions.countTypedEarnsByOperatorSince(any(), any())).thenReturn(100_000L);

        off.post(TENANT, MERCHANT, request(), null, EarnChannel.TYPED_PHONE);

        verify(transactions).saveAndFlush(any());
        verify(transactions, never()).countTypedEarnsByOperatorSince(any(), any());
    }

    @Test
    void aRefusedEarnIsRecordedAsEvidence() {
        when(transactions.countTypedEarnsByOperatorSince(eq(OPERATOR), any())).thenReturn(99L);

        assertThatThrownBy(() -> service.post(TENANT, MERCHANT, request(), null, EarnChannel.TYPED_PHONE))
                .isInstanceOf(LoyaltyException.class);

        verify(fraud).record(eq(TENANT), any(), eq(MERCHANT), any(),
                eq(FraudAttempt.Reason.EARN_VELOCITY), any(), any(), any());
    }

    @Test
    void theWindowIsRolling_notCalendarBound() {
        when(transactions.countTypedEarnsByOperatorSince(eq(OPERATOR), any())).thenReturn(0L);

        service.post(TENANT, MERCHANT, request(), null, EarnChannel.TYPED_PHONE);

        var since = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(transactions).countTypedEarnsByOperatorSince(eq(OPERATOR), since.capture());
        // Roughly WINDOW seconds back from now — a rolling window, so an
        // operator can't reset their count by waiting for midnight.
        long secondsBack = java.time.Duration.between(since.getValue(), Instant.now()).getSeconds();
        assertThat(secondsBack).isBetween((long) WINDOW - 30, (long) WINDOW + 30);
    }

    // ---- fixtures ----

    private TransactionService newService(LoyaltyProperties.Earn earn) {
        LoyaltyProperties props = new LoyaltyProperties(null, null, null, null, earn, null);
        return new TransactionService(
                transactions, users, merchants, walletService, rulesEngine,
                new LoyaltyMetrics(new SimpleMeterRegistry()),
                mock(MemberActivityNotifier.class),
                props, fraud, staffRegistry);
    }

    private static Dtos.TransactionRequest request() {
        return new Dtos.TransactionRequest(null, RECIPIENT, null, TransactionType.PURCHASE,
                new BigDecimal("100"), "USD", "RECEIPT-0001");
    }

    private static void authenticateAs(String... roles) {
        var auth = new UsernamePasswordAuthenticationToken(
                "cashier@example.com", null,
                List.of(roles).stream().map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new CallerDetails(null, null, "+263779999999", OPERATOR));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
