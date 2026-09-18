package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.integration.VoucherRedemptionRejectedEvent;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
 * The guards on voucher redemption, after the round of fixes that made three of
 * them actually guard something.
 *
 * <p>Each case below corresponds to a way the old path was wrong rather than to
 * a line of code:
 * <ul>
 *   <li>the holder of a voucher issued by {@code assignedUserId} alone was
 *       DELIVERED the code and then refused it, because the ownership check
 *       compared a live phone claim against the null {@code assigneePhone}
 *       column;</li>
 *   <li>the BLOCKED / registration gates hung off an optional body
 *       {@code userId}, so a till that sent only the code performed no account
 *       checks at all — and naming any unrelated ACTIVE account satisfied
 *       them;</li>
 *   <li>refusals wrote {@code voucher_redemptions} rows inside the transaction
 *       they then rolled back, so the table could only ever hold SUCCESS.</li>
 * </ul>
 */
class VoucherRedemptionGuardsTest {

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
    private final FraudService fraud = mock(FraudService.class);
    private final LoyaltyMetrics metrics = mock(LoyaltyMetrics.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final CryptoSigner signer = new CryptoSigner(VOUCHER_SECRET);

    private VoucherService service;

    @BeforeEach
    void setUp() {
        service = new VoucherService(vouchers, mock(VoucherBatchRepository.class), redemptions,
                merchants, merchantAuthz,
                new SupportedCurrencies("USD", "USD"),
                mock(LoyaltyRuleRepository.class), users, userService,
                mock(NotificationGateway.class), fraud, metrics,
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                new LoyaltyProperties(null, null, null, null, null, null, null),
                new ExchangeRateService(mock(ExchangeRateRepository.class),
                        new SupportedCurrencies("USD", "USD"), new BigDecimal("25")),
                events);
        when(metrics.redemptionLatency()).thenReturn(mock(io.micrometer.core.instrument.Timer.class));
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(new Merchant());
        // The staff authz mock REFUSES a caller who does not administer the
        // merchant, as the real one does — rather than waving everything through.
        // A mock stubbed to succeed unconditionally is what let the first draft
        // of this fix apply the STAFF rule to CUSTOMERS, which 403s every
        // self-redeem (including the whole /loyalty/public/** surface, whose
        // synthesised caller has no merchant claim), while every test here still
        // passed. A guard's mock has to be able to say no.
        when(merchantAuthz.requireCallerAdministersMerchant(TENANT, MERCHANT)).thenAnswer(inv -> {
            if (CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN")
                    || MERCHANT.equals(CallerDetails.currentMerchantId())) {
                return new Merchant();
            }
            throw LoyaltyException.forbidden("NOT_MERCHANT_OWNER",
                    "you are not authorised to act on this merchant");
        });
        when(redemptions.save(any())).thenAnswer(inv -> {
            VoucherRedemption r = inv.getArgument(0);
            r.setRedeemedAt(Instant.now());
            return r;
        });
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** A voucher whose holder is named ONLY by assignedUserId — no phone column. */
    private Voucher voucherAssignedByUserIdOnly(UUID holderId) {
        Voucher v = baseVoucher();
        v.setAssignedUserId(holderId);
        v.setAssigneePhone(null);
        return v;
    }

    private Voucher voucherAssignedByPhone(String phone) {
        Voucher v = baseVoucher();
        v.setAssignedUserId(null);
        v.setAssigneePhone(phone);
        return v;
    }

    /** Unassigned campaign stock: no holder of any kind. */
    private Voucher bulkStock() {
        Voucher v = baseVoucher();
        v.setAssignedUserId(null);
        v.setAssigneePhone(null);
        return v;
    }

    private Voucher baseVoucher() {
        Voucher v = new Voucher();
        v.setId(UUID.randomUUID());
        v.setTenantId(TENANT);
        v.setMerchantId(MERCHANT);
        v.setStatus(Voucher.Status.ISSUED);
        v.setUsesRemaining(1);
        v.setValue(new BigDecimal("5.00"));
        v.setCurrency("USD");
        String code = "ABCD2345EFGH";
        v.setCode(code);
        // signPayload with no template id (every post-V45 voucher).
        v.setSignature(signer.sign(TENANT + ":-:" + code));
        return v;
    }

    private LoyaltyUser account(UUID id, String phone, LoyaltyUser.Status status) {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(id);
        u.setTenantId(TENANT);
        u.setPhoneNumber(phone);
        u.setStatus(status);
        return u;
    }

    private Dtos.RedeemVoucherRequest request(Voucher v, UUID claimedUserId) {
        return new Dtos.RedeemVoucherRequest(MERCHANT, v.getCode(), claimedUserId,
                "WESTGATE", "device-1", "10.0.0.1");
    }

    private static void asCustomer(String phone) {
        var auth = new UsernamePasswordAuthenticationToken(
                "customer@test.local", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        auth.setDetails(new CallerDetails(null, null, phone, UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void asCashier() {
        var auth = new UsernamePasswordAuthenticationToken(
                "till@test.local", null, List.of(new SimpleGrantedAuthority("ROLE_SHOP_USER")));
        auth.setDetails(new CallerDetails(MERCHANT, null, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ------------------------------------------------------------------
    // the holder of an assignedUserId-only voucher can spend it
    // ------------------------------------------------------------------

    @Test
    void theHolderOfAVoucherAssignedByUserIdAloneCanRedeemIt() {
        UUID holderId = UUID.randomUUID();
        Voucher v = voucherAssignedByUserIdOnly(holderId);
        LoyaltyUser holder = account(holderId, HOLDER_PHONE, LoyaltyUser.Status.ACTIVE);
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));
        when(users.findById(holderId)).thenReturn(Optional.of(holder));
        when(userService.spendabilityOf(holder)).thenReturn(UserService.Spendability.OK);

        // The customer the voucher was issued TO, holding a phone-scoped session.
        asCustomer(HOLDER_PHONE);

        Dtos.RedemptionResponse resp = service.redeem(TENANT, MERCHANT, request(v, null));

        // Before the fix this was a 403 NOT_VOUCHER_OWNER: the check compared the
        // caller's phone against the null assigneePhone column, while delivery
        // had resolved the same holder's phone and sent them the code.
        assertThat(resp.status()).isEqualTo(Voucher.Status.REDEEMED.name());
        assertThat(v.getUsesRemaining()).isZero();
    }

    @Test
    void aDifferentCustomerStillCannotRedeemThatVoucher() {
        UUID holderId = UUID.randomUUID();
        Voucher v = voucherAssignedByUserIdOnly(holderId);
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));
        when(users.findById(holderId))
                .thenReturn(Optional.of(account(holderId, HOLDER_PHONE, LoyaltyUser.Status.ACTIVE)));

        asCustomer("+263770000111");

        assertThatThrownBy(() -> service.redeem(TENANT, MERCHANT, request(v, null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("NOT_VOUCHER_OWNER"));
        assertThat(v.getUsesRemaining()).isEqualTo(1);
    }

    @Test
    void aVoucherWithNoHolderAtAllIsNotRedeemableByACustomer() {
        // Resolving the holder must not turn "nobody owns this" into "everybody
        // owns this": bulk stock has no holder, so a customer bearer has nothing
        // to match and is refused. A cashier still redeems it (below).
        Voucher v = bulkStock();
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));

        asCustomer(HOLDER_PHONE);

        assertThatThrownBy(() -> service.redeem(TENANT, MERCHANT, request(v, null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("NOT_VOUCHER_OWNER"));
    }

    // ------------------------------------------------------------------
    // the account gate reads the VOUCHER, not the request
    // ------------------------------------------------------------------

    @Test
    void aBlockedHolderIsRefusedEvenWhenTheTillSendsNoUserId() {
        Voucher v = voucherAssignedByPhone(HOLDER_PHONE);
        LoyaltyUser holder = account(UUID.randomUUID(), HOLDER_PHONE, LoyaltyUser.Status.BLOCKED);
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));
        when(users.findByTenantIdAndPhoneNumber(TENANT, HOLDER_PHONE)).thenReturn(Optional.of(holder));
        when(userService.spendabilityOf(holder)).thenReturn(UserService.Spendability.BLOCKED);

        asCashier();

        // THE bypass: userId is null, which used to skip both account gates.
        assertThatThrownBy(() -> service.redeem(TENANT, MERCHANT, request(v, null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("USER_BLOCKED"));
        assertThat(v.getUsesRemaining()).isEqualTo(1);
    }

    @Test
    void namingAnUnrelatedActiveAccountDoesNotSatisfyTheGate() {
        Voucher v = voucherAssignedByPhone(HOLDER_PHONE);
        LoyaltyUser holder = account(UUID.randomUUID(), HOLDER_PHONE, LoyaltyUser.Status.BLOCKED);
        UUID someoneElse = UUID.randomUUID();
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));
        when(users.findByTenantIdAndPhoneNumber(TENANT, HOLDER_PHONE)).thenReturn(Optional.of(holder));
        when(userService.spendabilityOf(holder)).thenReturn(UserService.Spendability.BLOCKED);

        asCashier();

        // The other half of the same bypass: the body named a perfectly healthy
        // account, and the gate checked THAT one.
        assertThatThrownBy(() -> service.redeem(TENANT, MERCHANT, request(v, someoneElse)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("USER_BLOCKED"));
        // The claimed id is never even loaded — the voucher knows its holder.
        verify(users, never()).findById(someoneElse);
    }

    @Test
    void anInactiveHolderIsRefused_theVerdictTheVoucherGateUsedToLack() {
        Voucher v = voucherAssignedByPhone(HOLDER_PHONE);
        LoyaltyUser holder = account(UUID.randomUUID(), HOLDER_PHONE, LoyaltyUser.Status.INACTIVE);
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));
        when(users.findByTenantIdAndPhoneNumber(TENANT, HOLDER_PHONE)).thenReturn(Optional.of(holder));
        when(userService.spendabilityOf(holder)).thenReturn(UserService.Spendability.INACTIVE);

        asCashier();

        assertThatThrownBy(() -> service.redeem(TENANT, MERCHANT, request(v, null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("USER_INACTIVE"));
    }

    @Test
    void anUnauthenticatedRedeemOfBulkStockIsNotAskedToAdministerTheShopEither() {
        // The second non-staff shape, and the one a role check for "customer"
        // misses: PublicTestController.asCustomer installs a principal only when
        // the voucher HAS a holder, so unassigned stock redeemed through the
        // staging surface arrives with no authentication at all. It used to
        // reach requireMerchant; the first draft of this change sent it to the
        // staff rule, which refuses a caller with no roles.
        Voucher v = bulkStock();
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));
        SecurityContextHolder.clearContext();

        Dtos.RedemptionResponse resp = service.redeem(TENANT, MERCHANT, request(v, null));

        assertThat(resp.status()).isEqualTo(Voucher.Status.REDEEMED.name());
        verify(merchantAuthz, never()).requireCallerAdministersMerchant(any(), any());
        verify(merchants).requireMerchant(TENANT, MERCHANT);
    }

    @Test
    void bulkStockHasNoAccountToGateOn_andStillRedeemsAtATill() {
        Voucher v = bulkStock();
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));

        asCashier();

        Dtos.RedemptionResponse resp = service.redeem(TENANT, MERCHANT, request(v, null));

        assertThat(resp.status()).isEqualTo(Voucher.Status.REDEEMED.name());
        // No holder, so nothing to ask about — not a refusal.
        verify(userService, never()).spendabilityOf(any());
    }

    @Test
    void theGateAsksTheOneSharedDecision_notItsOwnCopyOfTheRules() {
        // The voucher gate had drifted from the points gate three ways (no
        // PENDING heal, no V44 on-demand eligibility, no INACTIVE). Delegating
        // to spendabilityOf is what stops that recurring, so pin the delegation
        // itself rather than only its outcomes.
        Voucher v = voucherAssignedByPhone(HOLDER_PHONE);
        LoyaltyUser holder = account(UUID.randomUUID(), HOLDER_PHONE, LoyaltyUser.Status.PENDING);
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));
        when(users.findByTenantIdAndPhoneNumber(TENANT, HOLDER_PHONE)).thenReturn(Optional.of(holder));
        when(userService.spendabilityOf(holder))
                .thenReturn(UserService.Spendability.PENDING_REGISTRATION);

        asCashier();

        assertThatThrownBy(() -> service.redeem(TENANT, MERCHANT, request(v, null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("USER_PENDING"));
        verify(userService).spendabilityOf(holder);
        // And never by reading the status itself, which is what diverged before.
        verify(userService, never()).isRegistrationPending(any());
    }

    // ------------------------------------------------------------------
    // refusals leave evidence
    // ------------------------------------------------------------------

    @Test
    void aRefusalPublishesItsAuditRowInsteadOfWritingOneThatWouldRollBack() {
        Voucher v = voucherAssignedByPhone(HOLDER_PHONE);
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));
        when(users.findByTenantIdAndPhoneNumber(TENANT, HOLDER_PHONE)).thenReturn(Optional.empty());

        asCustomer("+263770000111");
        UUID claimed = UUID.randomUUID();

        assertThatThrownBy(() -> service.redeem(TENANT, MERCHANT, request(v, claimed)))
                .isInstanceOf(LoyaltyException.class);

        // Nothing written here: an insert on this path is discarded by the throw
        // that follows it, which is why REJECTED rows never existed.
        verify(redemptions, never()).save(any());

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOf(VoucherRedemptionRejectedEvent.class);
        VoucherRedemptionRejectedEvent e = (VoucherRedemptionRejectedEvent) published.getValue();
        assertThat(e.voucherId()).isEqualTo(v.getId());
        assertThat(e.tenantId()).isEqualTo(TENANT);
        assertThat(e.merchantId()).isEqualTo(MERCHANT);
        assertThat(e.reason()).isEqualTo("not voucher assignee");
        // The forensic detail the old row carried, still carried.
        assertThat(e.outletCode()).isEqualTo("WESTGATE");
        assertThat(e.deviceFingerprint()).isEqualTo("device-1");
        assertThat(e.ipAddress()).isEqualTo("10.0.0.1");
        // The request's claim is recorded verbatim — it is evidence, not the
        // account that was gated on.
        assertThat(e.userId()).isEqualTo(claimed);
        assertThat(e.markExpired()).isFalse();
    }

    @Test
    void anExpiredRedemptionAsksForTheStatusFlipItCannotMakeItself() {
        Voucher v = voucherAssignedByPhone(HOLDER_PHONE);
        v.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));

        asCashier();

        assertThatThrownBy(() -> service.redeem(TENANT, MERCHANT, request(v, null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("EXPIRED"));

        // The flip is NOT applied in this transaction — it used to be, and was
        // discarded with it, while the Swagger promised it. It rides the event
        // instead, applied once the voucher's write lock is released.
        assertThat(v.getStatus()).isEqualTo(Voucher.Status.ISSUED);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        VoucherRedemptionRejectedEvent e = (VoucherRedemptionRejectedEvent) published.getValue();
        assertThat(e.reason()).isEqualTo("expired");
        assertThat(e.markExpired()).isTrue();
        verify(fraud).record(eq(TENANT), any(), eq(MERCHANT), eq(v.getCode()),
                eq(FraudAttempt.Reason.EXPIRED), anyString(), any(), any());
    }

    @Test
    void aSuccessfulRedemptionStillWritesItsRowInsideTheTransaction() {
        Voucher v = bulkStock();
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));

        asCashier();
        service.redeem(TENANT, MERCHANT, request(v, null));

        // The mirror image of the refusal case, and the reason the success path
        // was NOT moved onto the event: if the redemption rolls back, the row
        // saying it happened has to roll back with it.
        ArgumentCaptor<VoucherRedemption> saved = ArgumentCaptor.forClass(VoucherRedemption.class);
        verify(redemptions).save(saved.capture());
        assertThat(saved.getValue().getResult()).isEqualTo(VoucherRedemption.Result.SUCCESS);
        verify(events, never()).publishEvent(any());
    }

    // ------------------------------------------------------------------
    // ordering + merchant authorization
    // ------------------------------------------------------------------

    @Test
    void aRevokedAndExhaustedVoucherSaysREVOKED_notALREADY_REDEEMED() {
        Voucher v = voucherAssignedByPhone(HOLDER_PHONE);
        v.setStatus(Voucher.Status.REVOKED);
        v.setUsesRemaining(0);
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));

        asCashier();

        // Clients branch on `code`. Under the old ordering the exhaustion check
        // ran first, so a voucher an operator had deliberately cancelled after
        // its last use reported itself as merely spent.
        assertThatThrownBy(() -> service.redeem(TENANT, MERCHANT, request(v, null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("REVOKED"));
    }

    @Test
    void aStaffRedeemRunsObjectLevelMerchantAuthorization_likeIssueDoes() {
        Voucher v = bulkStock();
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));

        asCashier();
        service.redeem(TENANT, MERCHANT, request(v, null));

        // requireMerchant only proved the merchant existed in the tenant, so a
        // staff caller with no merchantId claim to pin it could name a merchant
        // it does not administer and burn that merchant's voucher.
        verify(merchantAuthz).requireCallerAdministersMerchant(TENANT, MERCHANT);
    }

    @Test
    void aStaffCallerWhoDoesNotAdministerTheMerchantIsRefused() {
        Voucher v = bulkStock();
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));

        // A multi-merchant MERCHANT_ADMIN: staff, but deliberately given no
        // merchantId claim, so nothing pinned which merchant it could name.
        var auth = new UsernamePasswordAuthenticationToken(
                "owner@test.local", null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT_ADMIN")));
        auth.setDetails(new CallerDetails(null, null, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(() -> service.redeem(TENANT, MERCHANT, request(v, null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER"));
        assertThat(v.getUsesRemaining()).isEqualTo(1);
    }

    @Test
    void aCustomerRedeemingTheirOwnVoucherIsNotAskedToAdministerTheShop() {
        // The regression the staff rule caused when it was applied to everyone:
        // a customer can never administer the merchant they are spending at, so
        // requiring it refused every self-redeem with NOT_MERCHANT_OWNER — and
        // /loyalty/public/** synthesises exactly such a caller. Their
        // authorization is the assignee check plus WRONG_MERCHANT, which already
        // pin the redemption to this voucher's own merchant.
        Voucher v = voucherAssignedByPhone(HOLDER_PHONE);
        LoyaltyUser holder = account(UUID.randomUUID(), HOLDER_PHONE, LoyaltyUser.Status.ACTIVE);
        when(vouchers.lockByCode(v.getCode())).thenReturn(Optional.of(v));
        when(users.findByTenantIdAndPhoneNumber(TENANT, HOLDER_PHONE)).thenReturn(Optional.of(holder));
        when(userService.spendabilityOf(holder)).thenReturn(UserService.Spendability.OK);

        asCustomer(HOLDER_PHONE);

        Dtos.RedemptionResponse resp = service.redeem(TENANT, MERCHANT, request(v, null));

        assertThat(resp.status()).isEqualTo(Voucher.Status.REDEEMED.name());
        verify(merchantAuthz, never()).requireCallerAdministersMerchant(any(), any());
        verify(merchants).requireMerchant(TENANT, MERCHANT);
    }
}
