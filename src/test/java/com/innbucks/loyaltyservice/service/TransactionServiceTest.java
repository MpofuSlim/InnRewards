package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the A01 per-merchant guard on {@link TransactionService#reverse}: a
 * merchant-scoped caller (SHOP_ADMIN carries a merchantId claim) may only
 * reverse its OWN merchant's transactions, and the guard fires before any
 * money moves. Tenant-admins / SUPER_ADMIN (no merchant claim) bypass, exactly
 * like the voucher-redeem WRONG_MERCHANT check.
 */
class TransactionServiceTest {

    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final UserService users = mock(UserService.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final WalletService walletService = mock(WalletService.class);
    private final RulesEngine rulesEngine = mock(RulesEngine.class);
    private final LoyaltyMetrics metrics = mock(LoyaltyMetrics.class);
    private final MemberActivityNotifier memberNotifier = mock(MemberActivityNotifier.class);
    // Real properties record: the compact constructor fills the defaults, so
    // this is exactly the shipped configuration (both earn guards ON).
    private final com.innbucks.loyaltyservice.config.LoyaltyProperties props =
            new com.innbucks.loyaltyservice.config.LoyaltyProperties(null, null, null, null, null);
    private final FraudService fraudService = mock(FraudService.class);

    private final TransactionService service = new TransactionService(
            transactions, users, merchants, walletService, rulesEngine, metrics, memberNotifier,
            props, fraudService);

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT_A = UUID.randomUUID();
    private static final UUID MERCHANT_B = UUID.randomUUID();

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAsMerchant(UUID merchantId) {
        var auth = new UsernamePasswordAuthenticationToken(
                "caller@test.local", null, List.of(new SimpleGrantedAuthority("ROLE_SHOP_ADMIN")));
        auth.setDetails(new CallerDetails(merchantId, null, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private LoyaltyTransaction original(UUID merchantId, LoyaltyTransaction.Status status) {
        LoyaltyTransaction t = new LoyaltyTransaction();
        t.setId(UUID.randomUUID());
        t.setTenantId(TENANT);
        t.setMerchantId(merchantId);
        t.setUserId(UUID.randomUUID());
        t.setType(TransactionType.PURCHASE);
        t.setPointsDelta(new BigDecimal("100"));
        t.setStatus(status);
        when(transactions.lockById(t.getId())).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void reverse_crossMerchantShopAdmin_isRejected_andNeverDebits() {
        LoyaltyTransaction orig = original(MERCHANT_A, LoyaltyTransaction.Status.POSTED);
        authenticateAsMerchant(MERCHANT_B); // SHOP_ADMIN for a different merchant

        assertThatThrownBy(() -> service.reverse(TENANT, orig.getId(), "refund"))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode()).isEqualTo("WRONG_MERCHANT"));

        // Guard fires before the compensating credit is written or applied.
        assertThat(orig.getStatus()).isEqualTo(LoyaltyTransaction.Status.POSTED);
        verify(transactions, never()).saveAndFlush(any());
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void reverse_ownMerchantShopAdmin_passesGuard() {
        // Same merchant → guard passes. We short-circuit on the ALREADY_REVERSED
        // check right after the (passed) merchant guard so we don't have to stub
        // the whole wallet-credit path — the assertion is that WRONG_MERCHANT did
        // NOT fire for the owning merchant.
        LoyaltyTransaction orig = original(MERCHANT_A, LoyaltyTransaction.Status.REVERSED);
        authenticateAsMerchant(MERCHANT_A);

        assertThatThrownBy(() -> service.reverse(TENANT, orig.getId(), "refund"))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode()).isEqualTo("ALREADY_REVERSED"));
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void reverse_tenantAdmin_noMerchantScope_bypassesGuard() {
        // No merchant claim (MERCHANT_ADMIN / SUPER_ADMIN) → currentMerchantId()
        // is null → guard is skipped; we progress to ALREADY_REVERSED, proving
        // the merchant guard did not block a legitimately tenant-scoped caller.
        LoyaltyTransaction orig = original(MERCHANT_A, LoyaltyTransaction.Status.REVERSED);
        // No authentication set.

        assertThatThrownBy(() -> service.reverse(TENANT, orig.getId(), "refund"))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode()).isEqualTo("ALREADY_REVERSED"));
    }

    // --- A04: amount validation + no-mint-from-nothing reversal --------------

    @Test
    void post_negativeAmount_isRejected_andNeverTouchesTheWallet() {
        UUID userId = UUID.randomUUID();
        com.innbucks.loyaltyservice.entity.Merchant m =
                mock(com.innbucks.loyaltyservice.entity.Merchant.class);
        when(m.getStatus()).thenReturn(com.innbucks.loyaltyservice.entity.Merchant.Status.ACTIVE);
        when(merchants.requireMerchant(TENANT, MERCHANT_A)).thenReturn(m);
        when(users.require(TENANT, userId))
                .thenReturn(mock(com.innbucks.loyaltyservice.entity.LoyaltyUser.class));

        com.innbucks.loyaltyservice.dto.Dtos.TransactionRequest req =
                new com.innbucks.loyaltyservice.dto.Dtos.TransactionRequest(
                        null, userId, null, TransactionType.PURCHASE,
                        new BigDecimal("-5"), null, null);

        assertThatThrownBy(() -> service.post(TENANT, MERCHANT_A, req, null,
                com.innbucks.loyaltyservice.entity.EarnChannel.CHECKOUT_S2S))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode()).isEqualTo("INVALID_AMOUNT"));
        // Never reaches the rules engine or the wallet.
        verify(rulesEngine, never()).evaluate(any(), any(), any(), any());
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void reverse_originalNeverCreditedWallet_creditsNothing() {
        // The "points from nothing" guard: the original's stored pointsDelta is
        // 100, but the LEDGER shows it never actually moved the wallet
        // (appliedForTransaction == 0 — e.g. a non-positive earn post() declined
        // to apply). The reversal must NOT credit 100 back; it compensates only
        // what the ledger says moved (0), so no wallet apply happens.
        LoyaltyTransaction orig = original(MERCHANT_A, LoyaltyTransaction.Status.POSTED);
        authenticateAsMerchant(MERCHANT_A);
        when(walletService.appliedForTransaction(orig.getId())).thenReturn(BigDecimal.ZERO);

        var resp = service.reverse(TENANT, orig.getId(), "refund");

        assertThat(orig.getStatus()).isEqualTo(LoyaltyTransaction.Status.REVERSED);
        assertThat(resp.pointsDelta()).isEqualByComparingTo("0");
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
    }

    // --- Earn-integrity guards (V32): SELF_EARN + REFERENCE_REQUIRED ---------

    private static final String CASHIER_PHONE = "+263771234567";
    private static final UUID CASHIER_USER_ID = UUID.randomUUID();
    private static final UUID SHOP = UUID.randomUUID();

    private static void authenticateAsCashier() {
        var auth = new UsernamePasswordAuthenticationToken(
                "till-1@test.local", null, List.of(new SimpleGrantedAuthority("ROLE_SHOP_USER")));
        auth.setDetails(new CallerDetails(MERCHANT_A, SHOP, CASHIER_PHONE, CASHIER_USER_ID));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private com.innbucks.loyaltyservice.entity.Merchant activeMerchant() {
        var m = mock(com.innbucks.loyaltyservice.entity.Merchant.class);
        when(m.getStatus()).thenReturn(com.innbucks.loyaltyservice.entity.Merchant.Status.ACTIVE);
        when(m.getId()).thenReturn(MERCHANT_A);
        when(merchants.requireMerchant(TENANT, MERCHANT_A)).thenReturn(m);
        return m;
    }

    private com.innbucks.loyaltyservice.entity.LoyaltyUser userWithPhone(String phone) {
        var u = mock(com.innbucks.loyaltyservice.entity.LoyaltyUser.class);
        when(u.getId()).thenReturn(UUID.randomUUID());
        when(u.getPhoneNumber()).thenReturn(phone);
        return u;
    }

    private static com.innbucks.loyaltyservice.dto.Dtos.TransactionRequest phoneEarn(
            String phone, String reference) {
        return new com.innbucks.loyaltyservice.dto.Dtos.TransactionRequest(
                null, null, phone, TransactionType.PURCHASE, new BigDecimal("50"), null, reference);
    }

    @Test
    void typedPhone_toCallersOwnPhone_isRefusedAndFraudLogged() {
        authenticateAsCashier();
        activeMerchant();
        // Build the recipient mock BEFORE the when(): userWithPhone() stubs a
        // fresh mock internally, and doing that inside thenReturn() nests one
        // stubbing inside another (UnfinishedStubbingException).
        var self = userWithPhone(CASHIER_PHONE);
        when(users.findOrCreatePending(TENANT, CASHIER_PHONE, MERCHANT_A)).thenReturn(self);

        assertThatThrownBy(() -> service.post(TENANT, MERCHANT_A,
                phoneEarn(CASHIER_PHONE, "RCT-001"),
                com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode()).isEqualTo("SELF_EARN"));

        // The attempt itself is evidence: fraud row recorded (REQUIRES_NEW, so
        // it survives the rollback of this refusal) and NO transaction row.
        verify(fraudService).record(org.mockito.ArgumentMatchers.eq(TENANT), any(), org.mockito.ArgumentMatchers.eq(MERCHANT_A), any(),
                org.mockito.ArgumentMatchers.eq(com.innbucks.loyaltyservice.entity.FraudAttempt.Reason.SELF_EARN),
                any(), any(), any());
        verify(transactions, never()).saveAndFlush(any());
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void typedPhone_saleEarnWithoutReference_isRefusedBeforeAnyState() {
        authenticateAsCashier();
        activeMerchant();

        assertThatThrownBy(() -> service.post(TENANT, MERCHANT_A,
                phoneEarn("+263772000000", null),
                com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode()).isEqualTo("REFERENCE_REQUIRED"));

        // Refused before recipient resolution: no lazy PENDING enrolment, no row.
        verify(users, never()).findOrCreatePending(any(), any(), any());
        verify(transactions, never()).saveAndFlush(any());
    }

    @Test
    void qrPresence_callerIsRecipient_isAllowed_thatIsThePoint() {
        // The QR consume flow credits the authenticated scanner — caller ==
        // recipient by DESIGN there, and the reference is optional. Both
        // typed-phone guards must stay out of its way.
        authenticateAsCashier();
        activeMerchant();
        var u = userWithPhone(CASHIER_PHONE);
        when(users.findOrCreatePending(TENANT, CASHIER_PHONE, MERCHANT_A)).thenReturn(u);
        when(transactions.findFirstByMerchantIdAndReference(any(), any())).thenReturn(Optional.empty());
        when(rulesEngine.evaluate(any(), any(), any(), any()))
                .thenReturn(new RulesEngine.Evaluation(BigDecimal.ZERO, null, null, null));

        var resp = service.post(TENANT, MERCHANT_A, phoneEarn(CASHIER_PHONE, null),
                com.innbucks.loyaltyservice.entity.EarnChannel.QR_PRESENCE);

        assertThat(resp).isNotNull();
        verify(fraudService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void attribution_isStampedOnTheRow() {
        authenticateAsCashier();
        activeMerchant();
        var recipient = userWithPhone("+263772000000");
        when(users.findOrCreatePending(TENANT, "+263772000000", MERCHANT_A)).thenReturn(recipient);
        when(transactions.findFirstByMerchantIdAndReference(any(), any())).thenReturn(Optional.empty());
        when(rulesEngine.evaluate(any(), any(), any(), any()))
                .thenReturn(new RulesEngine.Evaluation(BigDecimal.ZERO, null, null, null));

        service.post(TENANT, MERCHANT_A, phoneEarn("+263772000000", "RCT-002"),
                com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE);

        var captor = org.mockito.ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(transactions).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPostedBy()).isEqualTo(CASHIER_USER_ID);
        assertThat(captor.getValue().getChannel())
                .isEqualTo(com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE);
    }

    @Test
    void s2sChannel_noReference_noAuth_postsUnattributed() {
        // Guest / shop checkout and the ticketing accrual carry no JWT and
        // resolve recipients server-side: guards off, postedBy null — the
        // documented "unattributed but legitimate" shape.
        activeMerchant();
        var recipient = userWithPhone("+263772000000");
        when(users.findOrCreatePending(TENANT, "+263772000000", MERCHANT_A)).thenReturn(recipient);
        when(transactions.findFirstByMerchantIdAndReference(any(), any())).thenReturn(Optional.empty());
        when(rulesEngine.evaluate(any(), any(), any(), any()))
                .thenReturn(new RulesEngine.Evaluation(BigDecimal.ZERO, null, null, null));

        service.post(TENANT, MERCHANT_A, phoneEarn("+263772000000", null), null,
                com.innbucks.loyaltyservice.entity.EarnChannel.CHECKOUT_S2S);

        var captor = org.mockito.ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(transactions).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPostedBy()).isNull();
        assertThat(captor.getValue().getChannel())
                .isEqualTo(com.innbucks.loyaltyservice.entity.EarnChannel.CHECKOUT_S2S);
    }
}
