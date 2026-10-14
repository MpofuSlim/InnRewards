package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the caller-ownership guard on {@code /loyalty/redeem}: the JWT-facing
 * overload ({@code enforceCallerOwnership=true}) must block a caller redeeming
 * another user's balance and must never debit; the S2S overload (shop-checkout /
 * ticketing) must skip the check so those trusted internal flows keep working.
 */
class RedemptionServiceTest {

    private final UserService users = mock(UserService.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final WalletService walletService = mock(WalletService.class);
    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final LoyaltyMetrics metrics = mock(LoyaltyMetrics.class);
    private final RedemptionRateService rateService = mock(RedemptionRateService.class);
    private final com.innbucks.loyaltyservice.security.MerchantAuthz merchantAuthz =
            mock(com.innbucks.loyaltyservice.security.MerchantAuthz.class);
    private final com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier =
            mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class);
    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<RedemptionService> self =
            mock(org.springframework.beans.factory.ObjectProvider.class);

    private final RedemptionService service =
            new RedemptionService(users, merchants, walletService, transactions, metrics, rateService, memberNotifier, self,
                    new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD"),
                    usdOnlyFx(),
                    merchantAuthz);

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private static Dtos.RedemptionRequest req() {
        return new Dtos.RedemptionRequest(MERCHANT, USER, new BigDecimal("100"), "reason", "ref-1");
    }

    @Test
    void enforceCallerOwnership_blocksNonOwner_andNeverDebits() {
        LoyaltyUser target = new LoyaltyUser();
        target.setId(USER);
        when(users.require(TENANT, USER)).thenReturn(target);
        doThrow(LoyaltyException.forbidden("NOT_WALLET_OWNER", "you can only act on your own loyalty account"))
                .when(users).requireCallerMayRedeemFor(target);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, req(), true))
                .isInstanceOf(LoyaltyException.class);

        // The guard fires before any money moves or any merchant is resolved.
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
        verify(merchants, never()).requireMerchant(any(), any());
    }

    @Test
    void s2sOverload_skipsOwnershipCheck() {
        LoyaltyUser target = new LoyaltyUser();
        target.setId(USER);
        when(users.require(TENANT, USER)).thenReturn(target);
        // Short-circuit right after the (skipped) ownership check so we don't have
        // to stub the whole earn path — the assertion is that the S2S overload
        // never consults requireCallerOwnsOrIsAdmin.
        //
        // The stub mirrors what requireSpendable ACTUALLY throws (403). It used to
        // fabricate a 400, which made this read as documentation that USER_PENDING
        // is a bad-request — it isn't, and nothing else pinned the real status.
        // UserServiceTest now owns that contract; this stub just has to stop lying.
        doThrow(LoyaltyException.forbidden("USER_PENDING", "stub — see UserServiceTest for the real message"))
                .when(users).requireSpendable(target);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, req()))
                .isInstanceOf(LoyaltyException.class);

        verify(users, never()).requireCallerOwnsOrIsAdmin(any());
        verify(users, never()).requireCallerMayRedeemFor(any());
        verifyNoInteractions(merchantAuthz);
    }

    /** Real FX service on a USD-only allowlist: USD converts by identity without
     *  touching the repository, so no stubbing is needed. */
    private static ExchangeRateService usdOnlyFx() {
        return new ExchangeRateService(
                org.mockito.Mockito.mock(com.innbucks.loyaltyservice.repository.ExchangeRateRepository.class),
                new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD"),
                new java.math.BigDecimal("25"));
    }

    /**
     * A PENDING customer must be refused on redeem BEFORE anything moves, and
     * with the exact code + status the FE branches on. The wiring is what this
     * pins: {@code requireSpendable} is genuinely on the redeem path (it would
     * be easy to drop while refactoring), and its refusal reaches the caller
     * unchanged rather than being swallowed or remapped.
     *
     * <p>Pending accounts accrue but cannot spend, so a leaked debit here would
     * take points the customer has no way to have authorised yet.
     */
    @Test
    void pendingUser_isRefusedOnRedeem_andNeverDebits() {
        LoyaltyUser target = new LoyaltyUser();
        target.setId(USER);
        target.setStatus(LoyaltyUser.Status.PENDING);
        when(users.require(TENANT, USER)).thenReturn(target);
        doThrow(LoyaltyException.forbidden("USER_PENDING", "Your rewards account is still being set up."))
                .when(users).requireSpendable(target);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, req()))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("USER_PENDING");
                    org.assertj.core.api.Assertions.assertThat(ex.getStatus())
                            .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                });

        verify(walletService, never()).apply(any(), any(), any(), any(), any());
        verify(transactions, never()).save(any());
        verify(transactions, never()).saveAndFlush(any());
    }

    // ---- the idempotent replay is bound to the account it debited ----

    private LoyaltyUser stubSpendableUser(UUID id, String phone) {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(id);
        u.setTenantId(TENANT);
        u.setPhoneNumber(phone);
        when(users.require(TENANT, id)).thenReturn(u);
        com.innbucks.loyaltyservice.entity.Merchant m = new com.innbucks.loyaltyservice.entity.Merchant();
        m.setId(MERCHANT);
        m.setCurrency("USD");
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(m);
        return u;
    }

    private void stubPriorRedemption(UUID owner) {
        com.innbucks.loyaltyservice.entity.LoyaltyTransaction prior =
                new com.innbucks.loyaltyservice.entity.LoyaltyTransaction();
        prior.setId(UUID.fromString("44444444-5555-6666-7777-888888888888"));
        prior.setType(com.innbucks.loyaltyservice.entity.TransactionType.REDEMPTION);
        prior.setMerchantId(MERCHANT);
        prior.setUserId(owner);
        prior.setReference("ref-1");
        when(transactions.findFirstByMerchantIdAndReference(MERCHANT, "ref-1"))
                .thenReturn(java.util.Optional.of(prior));
    }

    @Test
    void replay_forTheSameCustomer_returnsTheOriginal_withNoSecondDebit() {
        stubSpendableUser(USER, "+263771230001");
        stubPriorRedemption(USER);
        com.innbucks.loyaltyservice.entity.Wallet w = new com.innbucks.loyaltyservice.entity.Wallet();
        w.setBalance(new BigDecimal("900"));
        when(walletService.mainWallet("+263771230001")).thenReturn(w);

        RedemptionService.RedemptionResult r = service.redeemPoints(TENANT, MERCHANT, req());

        org.assertj.core.api.Assertions.assertThat(r.transactionId())
                .isEqualTo(UUID.fromString("44444444-5555-6666-7777-888888888888"));
        org.assertj.core.api.Assertions.assertThat(r.balance()).isEqualByComparingTo("900");
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
        verify(transactions, never()).save(any());
        verify(transactions, never()).saveAndFlush(any());
    }

    /**
     * The oracle this closes: a staff caller resending a known REDEMPTION
     * reference with ANOTHER spendable account used to get a 200 carrying that
     * account's balance and no debit. Now a 409, and no wallet is even read —
     * neither the requested account's nor the original's.
     */
    @Test
    void replay_forADifferentCustomer_is409_withNoDebit_andNoBalanceRead() {
        stubSpendableUser(USER, "+263771230001");
        stubPriorRedemption(UUID.randomUUID()); // the reference belongs to someone else

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, req(), true))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("DUPLICATE_REFERENCE");
                    org.assertj.core.api.Assertions.assertThat(ex.getStatus())
                            .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                            .isEqualTo("transaction with this merchant reference already exists");
                });

        verify(walletService, never()).mainWallet(any());
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
        verify(transactions, never()).save(any());
        verify(transactions, never()).saveAndFlush(any());
        verifyNoInteractions(memberNotifier);
    }

    // ---- exactly one of userId / assigneePhone ----

    private static Dtos.RedemptionRequest byBoth(UUID userId, String phone) {
        return new Dtos.RedemptionRequest(MERCHANT, userId, new BigDecimal("100"), "reason", "ref-1",
                null, null, phone);
    }

    @Test
    void bothUserIdAndPhone_isRecipientRequired_andNothingIsLookedUp() {
        assertRecipientRequired(byBoth(USER, "+263771230001"));
    }

    @Test
    void neitherUserIdNorPhone_isRecipientRequired() {
        assertRecipientRequired(byBoth(null, null));
    }

    @Test
    void aBlankPhoneAlone_countsAsAbsent_andIsRecipientRequired() {
        assertRecipientRequired(byBoth(null, "   "));
    }

    @Test
    void aBlankPhoneBesideAUserId_countsAsAbsent_andTheUserIdPathRuns() {
        stubSpendableUser(USER, "+263771230001");
        stubPriorRedemption(USER);
        com.innbucks.loyaltyservice.entity.Wallet w = new com.innbucks.loyaltyservice.entity.Wallet();
        w.setBalance(new BigDecimal("900"));
        when(walletService.mainWallet("+263771230001")).thenReturn(w);

        service.redeemPoints(TENANT, MERCHANT, byBoth(USER, ""));

        verify(users).require(TENANT, USER);
        verify(users, never()).requireByPhone(any(), any());
        verify(users, never()).normalizePhone(any());
    }

    private void assertRecipientRequired(Dtos.RedemptionRequest req) {
        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, req, true))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("RECIPIENT_REQUIRED");
                    org.assertj.core.api.Assertions.assertThat(ex.getStatus())
                            .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                    org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                            .isEqualTo("supply exactly one of userId or assigneePhone");
                });
        verifyNoInteractions(users, merchants, walletService, transactions, merchantAuthz);
    }

    // ---- the phone path ----

    @Test
    void thePhonePath_resolvesThroughRequireByPhone_andNeverCreatesAnAccount() {
        LoyaltyUser holder = stubSpendableUser(USER, "+263771230001");
        when(users.normalizePhone("0771230001")).thenReturn("+263771230001");
        when(users.requireByPhone(TENANT, "+263771230001")).thenReturn(holder);
        stubPriorRedemption(USER);
        com.innbucks.loyaltyservice.entity.Wallet w = new com.innbucks.loyaltyservice.entity.Wallet();
        w.setBalance(new BigDecimal("900"));
        when(walletService.mainWallet("+263771230001")).thenReturn(w);

        service.redeemPoints(TENANT, MERCHANT, byBoth(null, "0771230001"), true);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(users);
        order.verify(users).normalizePhone("0771230001");
        order.verify(users).requireCallerMayRedeemForPhone("+263771230001");
        order.verify(users).requireByPhone(TENANT, "+263771230001");
        // Every existing gate then runs on the RESOLVED account.
        order.verify(users).requireCallerMayRedeemFor(holder);
        order.verify(users).requireSpendable(holder);
        verify(users, never()).findOrCreatePending(any(), any(), any());
        verify(users, never()).findOrEnrol(any(), any(), any());
        verify(users, never()).require(any(), any());
    }

    @Test
    void thePhonePath_unknownPhone_is404_andNothingElseRuns() {
        when(users.normalizePhone("+263771239999")).thenReturn("+263771239999");
        when(users.requireByPhone(TENANT, "+263771239999")).thenThrow(LoyaltyException.notFound("user"));

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, byBoth(null, "+263771239999"), true))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("404 NOT_FOUND");
                    org.assertj.core.api.Assertions.assertThat(ex.getMessage()).isEqualTo("user not found");
                });
        verify(users, never()).findOrCreatePending(any(), any(), any());
        verifyNoInteractions(merchants, walletService, transactions, merchantAuthz);
    }

    @Test
    void theS2sPhonePath_skipsTheCallerCheck_likeTheUserIdPath() {
        LoyaltyUser holder = stubSpendableUser(USER, "+263771230001");
        when(users.normalizePhone("+263771230001")).thenReturn("+263771230001");
        when(users.requireByPhone(TENANT, "+263771230001")).thenReturn(holder);
        doThrow(LoyaltyException.forbidden("USER_PENDING", "stub")).when(users).requireSpendable(holder);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, byBoth(null, "+263771230001")))
                .isInstanceOf(LoyaltyException.class);

        verify(users, never()).requireCallerMayRedeemForPhone(any());
        verify(users, never()).requireCallerMayRedeemFor(any());
        verifyNoInteractions(merchantAuthz);
    }
}
