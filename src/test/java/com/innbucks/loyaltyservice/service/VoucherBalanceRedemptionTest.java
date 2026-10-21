package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.exception.EligibilityCheckDeferred;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.exception.VoucherCodeGuessException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.ExchangeRateRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherBatchRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.CryptoSigner;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every voucher is a BALANCE voucher (V61, owner decision 2026-10-21). Pins the
 * redeem path's money rules: a partial use leaves PARTIALLY_USED with the rest
 * as the balance, a bill bigger than the balance applies what is left, no
 * amount spends everything, a reference makes a retry a replay, the per-use USD
 * share telescopes to the voucher's base value, the first use is flagged for
 * the FIXED fee, and the holder is messaged after each use — never with the
 * code, never on a replay.
 *
 * <p>Pure Mockito. Outside a transaction {@code AfterCommit} runs the hand-off
 * immediately, so the gateway mock sees exactly what would be sent after the
 * commit. The database half (the unique index, the row lock, the backfill) is
 * pinned by {@code VoucherBalancePostgresIT} and {@code VoucherBalanceMigrationIT}.
 */
class VoucherBalanceRedemptionTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final String HOLDER_PHONE = "+263786546765";
    private static final String VOUCHER_SECRET = "change-me-voucher-secret-change-me-voucher-secret";

    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final VoucherRedemptionRepository redemptions = mock(VoucherRedemptionRepository.class);
    private final LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);
    private final UserService userService = mock(UserService.class);
    private final MerchantAuthz merchantAuthz = mock(MerchantAuthz.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final NotificationGateway notifications = mock(NotificationGateway.class);
    private final LoyaltyMetrics metrics = mock(LoyaltyMetrics.class);
    private final CryptoSigner signer = new CryptoSigner(VOUCHER_SECRET);

    /** Every SUCCESS row the service wrote, in order. */
    private final List<VoucherRedemption> written = new ArrayList<>();

    private VoucherService service;

    @BeforeEach
    void setUp() {
        service = new VoucherService(vouchers, mock(VoucherBatchRepository.class), redemptions,
                merchants, merchantAuthz, new SupportedCurrencies("USD", "USD"),
                mock(LoyaltyRuleRepository.class), users, userService, notifications,
                mock(FraudService.class), metrics,
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                new LoyaltyProperties(null, null, null, null, null, null, null),
                new ExchangeRateService(mock(ExchangeRateRepository.class),
                        new SupportedCurrencies("USD", "USD"), new BigDecimal("25")),
                mock(ApplicationEventPublisher.class));
        when(metrics.redemptionLatency()).thenReturn(mock(io.micrometer.core.instrument.Timer.class));
        when(merchantAuthz.requireCallerAdministersMerchant(TENANT, MERCHANT)).thenReturn(new Merchant());
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(new Merchant());
        when(redemptions.save(any())).thenAnswer(inv -> stamp(inv.getArgument(0)));
        when(redemptions.saveAndFlush(any())).thenAnswer(inv -> stamp(inv.getArgument(0)));
        // "Has this voucher been used before?" answered from what was written.
        when(redemptions.existsByVoucherIdAndResult(any(), eq(VoucherRedemption.Result.SUCCESS)))
                .thenAnswer(inv -> written.stream().anyMatch(r -> r.getVoucherId().equals(inv.getArgument(0))));
        when(redemptions.findFirstByMerchantIdAndReferenceAndResult(any(), any(), any()))
                .thenAnswer(inv -> written.stream()
                        .filter(r -> r.getMerchantId().equals(inv.getArgument(0)))
                        .filter(r -> inv.getArgument(1).equals(r.getReference()))
                        .findFirst());
        asCashier();
    }

    private VoucherRedemption stamp(VoucherRedemption r) {
        if (r.getId() == null) r.setId(UUID.randomUUID());
        r.setRedeemedAt(Instant.now());
        written.add(r);
        return r;
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private Voucher balanceVoucher(String face, String base) {
        Voucher v = new Voucher();
        v.setId(UUID.randomUUID());
        v.setTenantId(TENANT);
        v.setMerchantId(MERCHANT);
        v.setStatus(Voucher.Status.ISSUED);
        v.setUsesRemaining(1);
        v.setValue(new BigDecimal(face));
        v.setRemainingBalance(new BigDecimal(face));
        v.setBaseValue(base == null ? null : new BigDecimal(base));
        v.setCurrency("USD");
        v.setAssigneePhone(HOLDER_PHONE);
        v.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        String code = "4829137605128368";
        v.setCode(code);
        v.setSignature(signer.sign(TENANT + ":-:" + code));
        when(vouchers.lockByCode(code)).thenReturn(Optional.of(v));
        LoyaltyUser holder = new LoyaltyUser();
        holder.setId(UUID.randomUUID());
        holder.setTenantId(TENANT);
        holder.setPhoneNumber(HOLDER_PHONE);
        holder.setStatus(LoyaltyUser.Status.ACTIVE);
        when(users.findByTenantIdAndPhoneNumber(TENANT, HOLDER_PHONE)).thenReturn(Optional.of(holder));
        when(userService.spendabilityOf(holder)).thenReturn(UserService.Spendability.OK);
        return v;
    }

    private Dtos.RedemptionResponse use(Voucher v, String amount, String reference) {
        return service.redeem(TENANT, MERCHANT, new Dtos.RedeemVoucherRequest(MERCHANT, v.getCode(), null,
                "TILL-3", "device-1", "10.0.0.1", amount == null ? null : new BigDecimal(amount), reference));
    }

    private static void asCashier() {
        var auth = new UsernamePasswordAuthenticationToken(
                "till@test.local", null, List.of(new SimpleGrantedAuthority("ROLE_SHOP_USER")));
        auth.setDetails(new CallerDetails(MERCHANT, null, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private List<NotificationGateway.VoucherUse> notices() {
        ArgumentCaptor<NotificationGateway.VoucherUse> c = ArgumentCaptor.forClass(NotificationGateway.VoucherUse.class);
        verify(notifications, atLeast(0)).notifyVoucherUsed(c.capture());
        return c.getAllValues();
    }

    // ------------------------------------------------------------------
    // how much a use takes
    // ------------------------------------------------------------------

    @Test
    void aPartialUse_leavesThePartUsedVoucherWithTheRestAsItsBalance() {
        Voucher v = balanceVoucher("50.00", "50.0000");

        Dtos.RedemptionResponse r = use(v, "20.00", null);

        assertThat(r.status()).isEqualTo("PARTIALLY_USED");
        assertThat(r.value()).isEqualByComparingTo("20");
        assertThat(r.remainingBalance()).isEqualByComparingTo("30");
        assertThat(r.usesRemaining()).isEqualTo(1);
        assertThat(r.currency()).isEqualTo("USD");
        assertThat(r.replayed()).isFalse();
        assertThat(v.getStatus()).isEqualTo(Voucher.Status.PARTIALLY_USED);
        assertThat(v.getRemainingBalance()).isEqualByComparingTo("30");
        assertThat(v.getRedeemedAt()).as("only a balance reaching zero is a full redemption").isNull();

        VoucherRedemption row = written.get(0);
        assertThat(row.getResult()).isEqualTo(VoucherRedemption.Result.SUCCESS);
        assertThat(row.getAmount()).isEqualByComparingTo("20");
        assertThat(row.getBalanceAfter()).isEqualByComparingTo("30");
        assertThat(row.getBaseAmount()).isEqualByComparingTo("20");
        assertThat(row.isFirstUse()).isTrue();
    }

    @Test
    void aBillBiggerThanTheBalance_appliesWhatIsLeft_andSpendsTheVoucher() {
        Voucher v = balanceVoucher("50.00", "50.0000");
        use(v, "20.00", null);

        Dtos.RedemptionResponse r = use(v, "45.00", null);

        assertThat(r.value()).as("never refused — what is left is applied").isEqualByComparingTo("30");
        assertThat(r.status()).isEqualTo("REDEEMED");
        assertThat(r.remainingBalance()).isEqualByComparingTo("0");
        assertThat(r.usesRemaining()).isZero();
        assertThat(v.getRedeemedAt()).isNotNull();
        assertThat(written.get(1).isFirstUse()).as("only the first use is the first use").isFalse();
    }

    @Test
    void noAmount_spendsTheWholeRemainingBalance() {
        Voucher v = balanceVoucher("50.00", "50.0000");
        use(v, "12.50", null);

        Dtos.RedemptionResponse r = use(v, null, null);

        assertThat(r.value()).isEqualByComparingTo("37.50");
        assertThat(r.status()).isEqualTo("REDEEMED");
    }

    @Test
    void aFiveDollarVoucherSpentInOneGo_behavesExactlyAsBefore() {
        Voucher v = balanceVoucher("5.00", "5.0000");

        Dtos.RedemptionResponse r = use(v, null, null);

        assertThat(r.status()).isEqualTo("REDEEMED");
        assertThat(r.usesRemaining()).isZero();
        assertThat(r.value()).isEqualByComparingTo("5");
        assertThat(written).singleElement().satisfies(row -> {
            assertThat(row.isFirstUse()).isTrue();
            assertThat(row.getBaseAmount()).isEqualByComparingTo("5");
        });
    }

    @Test
    void aSpentVoucher_isStillAlreadyRedeemed() {
        Voucher v = balanceVoucher("5.00", "5.0000");
        use(v, null, null);

        assertThatThrownBy(() -> use(v, "1.00", null))
                .isInstanceOf(LoyaltyException.class)
                .extracting("code").isEqualTo("ALREADY_REDEEMED");
    }

    @Test
    void anAmountWithFractionsOfACent_isRefusedBeforeAnyLookup() {
        Voucher v = balanceVoucher("50.00", "50.0000");

        assertThatThrownBy(() -> use(v, "1.005", null))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    assertThat(((LoyaltyException) e).getCode()).isEqualTo("AMOUNT_PRECISION");
                    assertThat(((LoyaltyException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(vouchers, never()).lockByCode(any());
        assertThat(use(v, "1.50", null).value()).as("whole cents are fine").isEqualByComparingTo("1.50");
    }

    // ------------------------------------------------------------------
    // USD share telescopes; FIXED fee flag
    // ------------------------------------------------------------------

    @Test
    void theUsdShares_ofUnevenUses_sumToExactlyTheVouchersBaseValue() {
        // A ZWG-style ratio: no use's share is a whole number of 1/10000ths, so
        // rounding each use on its own would drift; telescoping cannot.
        Voucher v = balanceVoucher("100.00", "3.3337");

        use(v, "33.33", null);
        use(v, "0.01", null);
        use(v, "33.33", null);
        use(v, "17.77", null);
        use(v, null, null);

        BigDecimal sum = written.stream().map(VoucherRedemption::getBaseAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("3.3337");
        assertThat(written).extracting(VoucherRedemption::isFirstUse)
                .containsExactly(true, false, false, false, false);
        assertThat(written.stream().map(VoucherRedemption::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100");
    }

    @Test
    void baseShare_isNullWithoutABaseValue_andExactAtBothEnds() {
        assertThat(VoucherService.baseShare(new BigDecimal("50"), null, new BigDecimal("50"), BigDecimal.ZERO))
                .isNull();
        assertThat(VoucherService.baseShare(new BigDecimal("50"), new BigDecimal("41.2345"),
                new BigDecimal("50"), BigDecimal.ZERO)).isEqualByComparingTo("41.2345");
    }

    // ------------------------------------------------------------------
    // reference: replay and DUPLICATE_REFERENCE
    // ------------------------------------------------------------------

    @Test
    void aRetryWithTheSameReference_replaysTheOriginal_andSpendsNothing() {
        Voucher v = balanceVoucher("50.00", "50.0000");
        Dtos.RedemptionResponse first = use(v, "20.00", "RCPT-1");

        Dtos.RedemptionResponse again = use(v, "20.00", "RCPT-1");

        assertThat(again.replayed()).isTrue();
        assertThat(again.redemptionId()).isEqualTo(first.redemptionId());
        assertThat(again.value()).isEqualByComparingTo("20");
        assertThat(again.remainingBalance()).isEqualByComparingTo("30");
        assertThat(again.reference()).isEqualTo("RCPT-1");
        assertThat(written).hasSize(1);
        assertThat(v.getRemainingBalance()).isEqualByComparingTo("30");
        verify(notifications, times(1)).notifyVoucherUsed(any());
    }

    @Test
    void retryingTheUseThatEmptiedTheVoucher_replaysIt_ratherThanAnsweringAlreadyRedeemed() {
        Voucher v = balanceVoucher("50.00", "50.0000");
        use(v, null, "RCPT-LAST");

        Dtos.RedemptionResponse again = use(v, null, "RCPT-LAST");

        assertThat(again.replayed()).isTrue();
        assertThat(again.status()).isEqualTo("REDEEMED");
        assertThat(again.value()).isEqualByComparingTo("50");
        assertThat(again.remainingBalance()).isEqualByComparingTo("0");
    }

    @Test
    void theSameReferenceOnAnotherVoucher_isDuplicateReference_andNeverACountedGuess() {
        Voucher first = balanceVoucher("50.00", "50.0000");
        use(first, "10.00", "RCPT-9");
        Voucher other = balanceVoucher("20.00", "20.0000");
        other.setCode("7183502649174053");
        other.setSignature(signer.sign(TENANT + ":-:" + other.getCode()));
        when(vouchers.lockByCode(other.getCode())).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> use(other, "5.00", "RCPT-9"))
                .isInstanceOf(LoyaltyException.class)
                .isNotInstanceOf(VoucherCodeGuessException.class)
                .satisfies(e -> {
                    assertThat(((LoyaltyException) e).getCode()).isEqualTo("DUPLICATE_REFERENCE");
                    assertThat(((LoyaltyException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage())
                            .isEqualTo("This reference was already used for another voucher redemption.");
                });
        assertThat(other.getRemainingBalance()).isEqualByComparingTo("20");
        assertThat(written).hasSize(1);
    }

    @Test
    void aLostReferenceRace_isRetriedOnce_andReplaysTheWinner() {
        Voucher v = balanceVoucher("50.00", "50.0000");
        // The first attempt's flush loses the unique index to a concurrent
        // winner for this same use; the retry's pre-check then finds that row.
        VoucherRedemption winner = new VoucherRedemption();
        winner.setId(UUID.randomUUID());
        winner.setVoucherId(v.getId());
        winner.setMerchantId(MERCHANT);
        winner.setReference("RCPT-RACE");
        winner.setAmount(new BigDecimal("20.0000"));
        winner.setBalanceAfter(new BigDecimal("30.0000"));
        winner.setRedeemedAt(Instant.now());
        doThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_voucher_redemption_merchant_reference\""))
                .when(redemptions).saveAndFlush(any());
        when(redemptions.findFirstByMerchantIdAndReferenceAndResult(MERCHANT, "RCPT-RACE",
                VoucherRedemption.Result.SUCCESS)).thenReturn(Optional.empty(), Optional.of(winner));

        Dtos.RedemptionResponse r = service.redeemIdempotent(TENANT, MERCHANT,
                new Dtos.RedeemVoucherRequest(MERCHANT, v.getCode(), null, null, null, null,
                        new BigDecimal("20.00"), "RCPT-RACE"));

        assertThat(r.replayed()).isTrue();
        assertThat(r.redemptionId()).isEqualTo(winner.getId());
    }

    @Test
    void anIntegrityFailureThatIsNotTheReferenceIndex_isNotDisguisedAsARace() {
        Voucher v = balanceVoucher("50.00", "50.0000");
        doThrow(new DataIntegrityViolationException(
                "violates check constraint \"chk_vouchers_remaining_balance\""))
                .when(redemptions).saveAndFlush(any());

        assertThatThrownBy(() -> use(v, "20.00", "RCPT-X")).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // the holder's message
    // ------------------------------------------------------------------

    @Test
    void theHolderIsToldAfterEveryUse_whatWasUsedAndWhatIsLeft() {
        Voucher v = balanceVoucher("50.00", "50.0000");

        use(v, "20.00", null);
        use(v, null, null);

        List<NotificationGateway.VoucherUse> sent = notices();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).holderPhone()).isEqualTo(HOLDER_PHONE);
        assertThat(sent.get(0).used()).isEqualByComparingTo("20");
        assertThat(sent.get(0).remaining()).isEqualByComparingTo("30");
        assertThat(sent.get(0).currency()).isEqualTo("USD");
        assertThat(sent.get(0).merchantId()).isEqualTo(MERCHANT);
        assertThat(sent.get(1).used()).isEqualByComparingTo("30");
        assertThat(sent.get(1).remaining()).isEqualByComparingTo("0");
    }

    @Test
    void anEligibilityDeferral_spendsNothing_writesNothing_andMessagesNobody() {
        // The deferral's first pass rolls back and is replayed; everything a use
        // leaves behind is decided AFTER the spend gate, so the pass that is
        // thrown away has nothing to leave.
        Voucher v = balanceVoucher("50.00", "50.0000");
        LoyaltyUser holder = users.findByTenantIdAndPhoneNumber(TENANT, HOLDER_PHONE).orElseThrow();
        when(userService.spendabilityOf(holder)).thenThrow(new EligibilityCheckDeferred(HOLDER_PHONE));

        assertThatThrownBy(() -> use(v, "20.00", "RCPT-D")).isInstanceOf(EligibilityCheckDeferred.class);

        assertThat(written).isEmpty();
        assertThat(v.getRemainingBalance()).isEqualByComparingTo("50");
        verify(notifications, never()).notifyVoucherUsed(any());
    }

    @Test
    void aVoucherIssuedWithChannelNONE_isNotMessagedOnUse() {
        Voucher v = balanceVoucher("50.00", "50.0000");
        v.setDeliveryChannel(Voucher.DeliveryChannel.NONE);

        use(v, "20.00", null);

        verify(notifications, never()).notifyVoucherUsed(any());
    }

    @Test
    void aLegacyVoucherWithNoMoneyBalance_isSpentWholeAsBefore_andIgnoresTheAmount() {
        Voucher v = balanceVoucher("10.00", null);
        v.setRemainingBalance(null);   // a pre-V45 PERCENT row: "10% off" is not 10 of anything

        Dtos.RedemptionResponse r = use(v, "3.00", null);

        assertThat(r.status()).isEqualTo("REDEEMED");
        assertThat(r.value()).isEqualByComparingTo("10");
        assertThat(r.remainingBalance()).isNull();
        assertThat(v.getRemainingBalance()).isNull();
        verify(notifications, never()).notifyVoucherUsed(any());
    }
}
