package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
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
 * Ceilings on manual point adjustment.
 *
 * <p>Adjustment is the only path in the service that mints points from nothing:
 * earning is bounded by a real transaction amount and a rule's earn rate,
 * redemption by the balance. Before this guard a single SHOP_ADMIN could credit
 * an arbitrary figure to any account in their tenant — including one they
 * control — and redeem it immediately, with nothing but after-the-fact
 * attribution to show for it.
 *
 * <p>Pure JUnit + Mockito per the no-Docker convention.
 */
class AdjustmentCapTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID OPERATOR = UUID.randomUUID();

    private static final BigDecimal PER_CAP = new BigDecimal("5000");
    private static final BigDecimal DAILY_CAP = new BigDecimal("20000");

    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final UserService users = mock(UserService.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final WalletService walletService = mock(WalletService.class);
    private final FraudService fraud = mock(FraudService.class);
    private TransactionService service;

    @BeforeEach
    void setUp() {
        LoyaltyProperties props = new LoyaltyProperties(
                null, null, null, null, null,
                new LoyaltyProperties.Adjustment(PER_CAP, DAILY_CAP));

        service = new TransactionService(
                transactions, users, merchants, walletService,
                mock(RulesEngine.class),
                new LoyaltyMetrics(new SimpleMeterRegistry()),
                mock(MemberActivityNotifier.class),
                props, fraud, mock(StaffRegistry.class));

        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setCurrency("USD");
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(m);

        LoyaltyUser u = new LoyaltyUser();
        u.setId(TARGET);
        u.setPhoneNumber("+263771234567");
        when(users.require(TENANT, TARGET)).thenReturn(u);

        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        when(walletService.mainWallet(any())).thenReturn(w);
        when(walletService.apply(any(), any(), any(), any(), any()))
                .thenReturn(new BigDecimal("100"));
        when(transactions.sumAbsAdjustmentsByOperatorSince(any(), any()))
                .thenReturn(BigDecimal.ZERO);

        authenticateAs("ROLE_SHOP_ADMIN");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- per-adjustment ceiling ----

    @Test
    void anAdjustmentWithinTheCap_isAllowed() {
        service.adjust(TENANT, TARGET, MERCHANT, new BigDecimal("4999"), "counter fix");
        verify(transactions).save(any());
    }

    @Test
    void anAdjustmentExactlyAtTheCap_isAllowed() {
        // The cap is a ceiling, not an exclusive bound — "up to 5000" has to
        // include 5000 or the configured number means something other than it says.
        service.adjust(TENANT, TARGET, MERCHANT, PER_CAP, "counter fix");
        verify(transactions).save(any());
    }

    @Test
    void anAdjustmentOverTheCap_isRefusedAndNothingIsWritten() {
        assertThatThrownBy(() -> service.adjust(TENANT, TARGET, MERCHANT,
                new BigDecimal("5001"), "oops"))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("larger than you're allowed");

        // The whole point: no ledger row, no wallet movement.
        verify(transactions, never()).save(any());
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void aLargeDEBITIsCappedToo_notJustACredit() {
        // Wiping a customer's balance is the shape a disgruntled operator uses.
        // The ceiling compares magnitude, so sign is irrelevant.
        assertThatThrownBy(() -> service.adjust(TENANT, TARGET, MERCHANT,
                new BigDecimal("-9000"), "spite"))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("larger than you're allowed");

        verify(transactions, never()).save(any());
    }

    // ---- rolling 24h per-operator ceiling ----

    @Test
    void slicingOneLargeAdjustmentIntoManySmallOnes_hitsTheDailyCeiling() {
        // The obvious evasion of a per-adjustment cap. Each individual amount
        // here is well under PER_CAP; it is the running total that refuses.
        when(transactions.sumAbsAdjustmentsByOperatorSince(eq(OPERATOR), any()))
                .thenReturn(new BigDecimal("19500"));

        assertThatThrownBy(() -> service.adjust(TENANT, TARGET, MERCHANT,
                new BigDecimal("600"), "slice 40"))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("daily adjustment limit");

        verify(transactions, never()).save(any());
    }

    @Test
    void anAdjustmentThatLandsExactlyOnTheDailyCeiling_isAllowed() {
        when(transactions.sumAbsAdjustmentsByOperatorSince(eq(OPERATOR), any()))
                .thenReturn(new BigDecimal("19500"));

        service.adjust(TENANT, TARGET, MERCHANT, new BigDecimal("500"), "last one");

        verify(transactions).save(any());
    }

    @Test
    void theDailyTotalIsScopedToTheOPERATOR_notTheTargetOrMerchant() {
        // What is being rate-limited is a person's authority to mint points, so
        // spreading adjustments across many customers must not reset the count.
        service.adjust(TENANT, TARGET, MERCHANT, new BigDecimal("100"), "fix");

        verify(transactions).sumAbsAdjustmentsByOperatorSince(eq(OPERATOR), any(Instant.class));
    }

    // ---- exemptions and edges ----

    @Test
    void superAdminIsExemptFromBothCeilings() {
        // The aim is that a large correction requires someone accountable, not
        // that it becomes impossible.
        authenticateAs("ROLE_SUPER_ADMIN");
        when(transactions.sumAbsAdjustmentsByOperatorSince(any(), any()))
                .thenReturn(new BigDecimal("999999"));

        service.adjust(TENANT, TARGET, MERCHANT, new BigDecimal("1000000"), "migration correction");

        verify(transactions).save(any());
        // Not even queried — the exemption short-circuits before the sum.
        verify(transactions, never()).sumAbsAdjustmentsByOperatorSince(any(), any());
    }

    @Test
    void aZeroAdjustmentIsNeverBlocked() {
        service.adjust(TENANT, TARGET, MERCHANT, BigDecimal.ZERO, "no-op");
        verify(transactions).save(any());
    }

    @Test
    void aRefusedAdjustmentIsRecordedAsEvidence() {
        // FraudService.record runs REQUIRES_NEW so the row survives the
        // rollback this rejection causes. A refused over-cap adjustment is
        // exactly the signal an operator review wants, and it would be
        // worthless if it vanished with the rejection.
        assertThatThrownBy(() -> service.adjust(TENANT, TARGET, MERCHANT,
                new BigDecimal("50000"), "nope"))
                .isInstanceOf(LoyaltyException.class);

        verify(fraud).record(eq(TENANT), eq(TARGET), eq(MERCHANT), any(),
                eq(FraudAttempt.Reason.ADJUSTMENT_LIMIT), any(), any(), any());
    }

    @Test
    void aZeroCapDisablesThatHalfOfTheCheck() {
        // Escape hatch: an operator who needs the ceiling off for a migration
        // can set it to 0 rather than being forced to hand out SUPER_ADMIN.
        LoyaltyProperties off = new LoyaltyProperties(
                null, null, null, null, null,
                new LoyaltyProperties.Adjustment(BigDecimal.ZERO, BigDecimal.ZERO));
        TransactionService unlimited = new TransactionService(
                transactions, users, merchants, walletService,
                mock(RulesEngine.class),
                new LoyaltyMetrics(new SimpleMeterRegistry()),
                mock(MemberActivityNotifier.class),
                off, fraud, mock(StaffRegistry.class));

        unlimited.adjust(TENANT, TARGET, MERCHANT, new BigDecimal("999999"), "migration");

        verify(transactions).save(any());
    }

    private static void authenticateAs(String... roles) {
        var auth = new UsernamePasswordAuthenticationToken(
                "operator@example.com", null,
                List.of(roles).stream().map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new CallerDetails(null, null, "+263779999999", OPERATOR));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
