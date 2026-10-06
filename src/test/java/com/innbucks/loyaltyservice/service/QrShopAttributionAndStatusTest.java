package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.EarnChannel;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.QrToken;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.QrTokenRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C3: a merchant QR's earn is attributed to the till that ISSUED it, and the
 * issuer can ask whether it was scanned ({@code POST /loyalty/qr/status}).
 *
 * <p>Real {@link MerchantAuthz} over mocked repositories, and every token is
 * minted through the real {@code issue}, so consume gets past signature,
 * reuse and expiry exactly as in production.
 */
class QrShopAttributionAndStatusTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID OTHER_MERCHANT = UUID.randomUUID();
    private static final UUID ISSUING_SHOP = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final String CUSTOMER_PHONE = "+263770000202";
    private static final String STRANGER_PHONE = "+263770000303";

    private final QrTokenRepository qrs = mock(QrTokenRepository.class);
    private final TransactionService transactionService = mock(TransactionService.class);
    private final TransferService transferService = mock(TransferService.class);
    private final UserService userService = mock(UserService.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);

    private final QrService qrService = new QrService(
            qrs, transactionService, transferService, mock(FraudService.class), userService,
            new MerchantAuthz(merchants, mock(ShopRepository.class)), mock(StaffRegistry.class),
            new LoyaltyProperties(null, new LoyaltyProperties.Qr(
                    "unit-test-qr-secret-unit-test-qr-secret-unit-test", 300), null, null, null, null, null),
            new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD"));

    private LoyaltyUser customer;

    @BeforeEach
    void seed() {
        for (UUID id : List.of(MERCHANT, OTHER_MERCHANT)) {
            Merchant m = new Merchant();
            m.setId(id);
            m.setTenantId(TENANT);
            m.setOrganizationId(id.equals(MERCHANT) ? ORG : UUID.randomUUID());
            when(merchants.findById(id)).thenReturn(Optional.of(m));
        }
        customer = user(CUSTOMER_PHONE);
        when(qrs.save(any(QrToken.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---- attribution ----

    @Test
    void aMerchantQrIssuedAtATill_remembersThatShop_andItsEarnIsPostedThere() {
        QrToken q = issueMerchantQrAsShopAdmin();
        assertThat(q.getShopId()).isEqualTo(ISSUING_SHOP);

        UUID earnId = UUID.randomUUID();
        stubEarn(earnId, "40.0000");
        // The scanner is a customer: NO shop claim on their token.
        authenticate("ROLE_CUSTOMER", null, null, CUSTOMER_PHONE, null);
        qrService.consume(TENANT, consume(q));

        verify(transactionService).postForShop(eq(TENANT), eq(MERCHANT), any(Dtos.TransactionRequest.class),
                eq(ISSUING_SHOP), eq(EarnChannel.QR_PRESENCE));
        verify(transactionService, never()).post(any(), any(), any(), any(EarnChannel.class));
        assertThat(q.getTransactionId()).isEqualTo(earnId);
        assertThat(q.getPointsAwarded()).isEqualByComparingTo("40.0000");
    }

    @Test
    void aScannerWithAShopClaimOfItsOwn_neverRedirectsTheEarn() {
        QrToken q = issueMerchantQrAsShopAdmin();
        stubEarn(UUID.randomUUID(), "40.0000");
        // Staff of ANOTHER merchant shopping here: their shop must not get the earn.
        authenticate("ROLE_SHOP_ADMIN", OTHER_MERCHANT, UUID.randomUUID(), CUSTOMER_PHONE, null);
        qrService.consume(TENANT, consume(q));

        verify(transactionService).postForShop(any(), any(), any(), eq(ISSUING_SHOP), any());
    }

    @Test
    void aMerchantQrIssuedByAMerchantAdmin_carriesNoShop() {
        authenticate("ROLE_MERCHANT_ADMIN", null, null, null, ORG);
        QrToken q = issue(QrToken.SourceType.MERCHANT, MERCHANT);
        assertThat(q.getShopId()).isNull();
    }

    // ---- status: PENDING -> CONSUMED, EXPIRED ----

    @Test
    void status_goesFromPendingToConsumed_forTheIssuingTill() {
        QrToken q = issueMerchantQrAsShopAdmin();

        authenticate("ROLE_SHOP_USER", MERCHANT, UUID.randomUUID(), "+263770000999", null);
        Dtos.QrStatusResponse before = qrService.status(TENANT, q.getToken());
        assertThat(before.status()).isEqualTo(Dtos.QrStatus.PENDING);
        assertThat(before.expiresAt()).isEqualTo(q.getExpiresAt());
        assertThat(before.consumedAt()).isNull();
        assertThat(before.transactionId()).isNull();
        assertThat(before.pointsAwarded()).isNull();

        UUID earnId = UUID.randomUUID();
        stubEarn(earnId, "40.0000");
        authenticate("ROLE_CUSTOMER", null, null, CUSTOMER_PHONE, null);
        qrService.consume(TENANT, consume(q));

        authenticate("ROLE_SHOP_USER", MERCHANT, UUID.randomUUID(), "+263770000999", null);
        Dtos.QrStatusResponse after = qrService.status(TENANT, q.getToken());
        assertThat(after.status()).isEqualTo(Dtos.QrStatus.CONSUMED);
        assertThat(after.consumedAt()).isEqualTo(q.getUsedAt()).isNotNull();
        assertThat(after.transactionId()).isEqualTo(earnId);
        assertThat(after.pointsAwarded()).isEqualByComparingTo("40.0000");
    }

    @Test
    void status_reportsExpired_forAnUnscannedQrPastItsTtl_butConsumedWinsOverExpiry() {
        QrToken q = issueMerchantQrAsShopAdmin();
        q.setExpiresAt(Instant.now().minusSeconds(5));

        authenticate("ROLE_SHOP_ADMIN", MERCHANT, ISSUING_SHOP, null, null);
        assertThat(qrService.status(TENANT, q.getToken()).status()).isEqualTo(Dtos.QrStatus.EXPIRED);

        q.setUsedAt(Instant.now().minusSeconds(60));
        assertThat(qrService.status(TENANT, q.getToken()).status()).isEqualTo(Dtos.QrStatus.CONSUMED);
    }

    @Test
    void status_isReadableByTheMerchantAdminsOrganization_andSuperAdmin() {
        QrToken q = issueMerchantQrAsShopAdmin();

        authenticate("ROLE_MERCHANT_ADMIN", null, null, null, ORG);
        assertThat(qrService.status(TENANT, q.getToken()).status()).isEqualTo(Dtos.QrStatus.PENDING);

        authenticate("ROLE_SUPER_ADMIN", null, null, null, null);
        assertThat(qrService.status(TENANT, q.getToken()).status()).isEqualTo(Dtos.QrStatus.PENDING);
    }

    // ---- status: refusals are the consume-path 404 ----

    @Test
    void status_anotherMerchantsStaff_getTheUnknownToken404() {
        QrToken q = issueMerchantQrAsShopAdmin();

        authenticate("ROLE_SHOP_USER", OTHER_MERCHANT, UUID.randomUUID(), null, null);
        assertUnknown(() -> qrService.status(TENANT, q.getToken()));

        authenticate("ROLE_MERCHANT_ADMIN", null, null, null, UUID.randomUUID());
        assertUnknown(() -> qrService.status(TENANT, q.getToken()));
    }

    @Test
    void status_aCustomerCannotReadAMerchantQr() {
        QrToken q = issueMerchantQrAsShopAdmin();
        authenticate("ROLE_CUSTOMER", null, null, CUSTOMER_PHONE, null);
        assertUnknown(() -> qrService.status(TENANT, q.getToken()));
    }

    @Test
    void status_aTransferQr_isVisibleToItsSenderOnly() {
        authenticate("ROLE_CUSTOMER", null, null, CUSTOMER_PHONE, null);
        QrToken q = issue(QrToken.SourceType.USER, customer.getId());
        assertThat(qrService.status(TENANT, q.getToken()).status()).isEqualTo(Dtos.QrStatus.PENDING);

        // A different customer — and an admin: no bypass on someone's transfer QR.
        authenticate("ROLE_CUSTOMER", null, null, STRANGER_PHONE, null);
        assertUnknown(() -> qrService.status(TENANT, q.getToken()));
        authenticate("ROLE_MERCHANT_ADMIN", null, null, STRANGER_PHONE, ORG);
        assertUnknown(() -> qrService.status(TENANT, q.getToken()));
    }

    @Test
    void status_aTransferQrConsume_recordsThePointsMoved() {
        authenticate("ROLE_CUSTOMER", null, null, CUSTOMER_PHONE, null);
        QrToken q = issue(QrToken.SourceType.USER, customer.getId());

        LoyaltyUser recipient = user(STRANGER_PHONE);
        authenticate("ROLE_CUSTOMER", null, null, STRANGER_PHONE, null);
        qrService.consume(TENANT, new Dtos.QrConsumeRequest(q.getToken(), q.getSignature(), recipient.getId(), null));

        authenticate("ROLE_CUSTOMER", null, null, CUSTOMER_PHONE, null);
        Dtos.QrStatusResponse s = qrService.status(TENANT, q.getToken());
        assertThat(s.status()).isEqualTo(Dtos.QrStatus.CONSUMED);
        assertThat(s.transactionId()).isNull();
        assertThat(s.pointsAwarded()).isEqualByComparingTo("40.00");
    }

    @Test
    void status_unknownOrOtherTenantToken_isThe404_andNothingElseIsAsked() {
        authenticate("ROLE_SUPER_ADMIN", null, null, null, null);
        when(qrs.findByToken("nope")).thenReturn(Optional.empty());
        assertUnknown(() -> qrService.status(TENANT, "nope"));

        QrToken q = issueMerchantQrAsShopAdmin();
        authenticate("ROLE_SUPER_ADMIN", null, null, null, null);
        assertUnknown(() -> qrService.status(UUID.randomUUID(), q.getToken()));
        verifyNoInteractions(transactionService);
    }

    // ---- helpers ----

    private QrToken issueMerchantQrAsShopAdmin() {
        authenticate("ROLE_SHOP_ADMIN", MERCHANT, ISSUING_SHOP, "+263770000101", null);
        QrToken q = issue(QrToken.SourceType.MERCHANT, MERCHANT);
        SecurityContextHolder.clearContext();
        return q;
    }

    private QrToken issue(QrToken.SourceType type, UUID sourceId) {
        org.mockito.Mockito.clearInvocations(qrs);
        qrService.issue(TENANT, new Dtos.QrIssueRequest(type, sourceId, TransactionType.QR_PAY,
                new BigDecimal("40.00"), "USD", null));
        ArgumentCaptor<QrToken> saved = ArgumentCaptor.forClass(QrToken.class);
        verify(qrs).save(saved.capture());
        QrToken q = saved.getValue();
        when(qrs.lockByToken(q.getToken())).thenReturn(Optional.of(q));
        when(qrs.findByToken(q.getToken())).thenReturn(Optional.of(q));
        return q;
    }

    private Dtos.QrConsumeRequest consume(QrToken q) {
        return new Dtos.QrConsumeRequest(q.getToken(), q.getSignature(), customer.getId(), "POS-1");
    }

    private void stubEarn(UUID id, String points) {
        when(transactionService.postForShop(any(), any(), any(), any(), any()))
                .thenReturn(new Dtos.TransactionResponse(id, TransactionType.QR_PAY,
                        new BigDecimal("40.00"), new BigDecimal(points), new BigDecimal("140.0000"),
                        null, null, ISSUING_SHOP, null, EarnChannel.QR_PRESENCE, "POS-1",
                        Instant.now(), null, "USD", new BigDecimal("40.00")));
    }

    private LoyaltyUser user(String phone) {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(UUID.randomUUID());
        u.setTenantId(TENANT);
        u.setPhoneNumber(phone);
        when(userService.require(TENANT, u.getId())).thenReturn(u);
        // The real strict rule, so issue/consume authorize exactly as in production.
        org.mockito.Mockito.doAnswer(inv -> {
            String caller = CallerDetails.currentPhoneNumber();
            if (caller == null || !caller.equals(u.getPhoneNumber())) {
                throw LoyaltyException.forbidden("NOT_WALLET_OWNER", "you can only act on your own loyalty account");
            }
            return null;
        }).when(userService).requireCallerOwns(u);
        return u;
    }

    private static void assertUnknown(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LoyaltyException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
            assertThat(e.getCode()).isEqualTo("NOT_FOUND");
            assertThat(e.getMessage()).isEqualTo("This QR code is invalid or has expired.");
        });
    }

    private static void authenticate(String role, UUID merchantId, UUID shopId, String phone, UUID org) {
        var auth = new UsernamePasswordAuthenticationToken("caller@test.local", null,
                List.of(new SimpleGrantedAuthority(role)));
        auth.setDetails(new CallerDetails(merchantId, shopId, phone, UUID.randomUUID(), org));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
