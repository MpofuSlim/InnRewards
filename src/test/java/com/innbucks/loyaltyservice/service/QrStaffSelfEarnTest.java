package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.EarnChannel;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A merchant QR is shown at the counter for the CUSTOMER to scan. The staff
 * behind that counter must not be able to scan it for themselves: the
 * QR_PRESENCE channel skips the typed-phone earn guards on the premise that the
 * scanner is the customer, so {@code QrService.consume} checks that premise.
 *
 * <p>Each case mints a real signed token through {@code issue} (as the
 * merchant's admin), so consume gets past the signature, reuse and expiry
 * checks and reaches the guard under test.
 */
class QrStaffSelfEarnTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final String CASHIER_PHONE = "+263770000101";
    private static final String CUSTOMER_PHONE = "+263770000202";

    private final QrTokenRepository qrs = mock(QrTokenRepository.class);
    private final TransactionService transactionService = mock(TransactionService.class);
    private final FraudService fraud = mock(FraudService.class);
    private final UserService userService = mock(UserService.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final StaffRegistry staffRegistry = mock(StaffRegistry.class);

    private final QrService qrService = new QrService(
            qrs, transactionService, mock(TransferService.class), fraud, userService,
            new MerchantAuthz(merchants, mock(ShopRepository.class)), staffRegistry,
            new LoyaltyProperties(null, new LoyaltyProperties.Qr(
                    "unit-test-qr-secret-unit-test-qr-secret-unit-test", 300), null, null, null, null, null),
            new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD"));

    private QrToken token;
    private Dtos.QrPayload payload;

    @BeforeEach
    void mintAMerchantQr() {
        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setTenantId(TENANT);
        when(merchants.findById(MERCHANT)).thenReturn(Optional.of(m));

        authenticate("ROLE_SUPER_ADMIN", null, null);
        payload = qrService.issue(TENANT, new Dtos.QrIssueRequest(
                QrToken.SourceType.MERCHANT, MERCHANT, TransactionType.QR_PAY,
                new BigDecimal("20.00"), "USD", null));
        ArgumentCaptor<QrToken> saved = ArgumentCaptor.forClass(QrToken.class);
        verify(qrs).save(saved.capture());
        token = saved.getValue();
        when(qrs.lockByToken(token.getToken())).thenReturn(Optional.of(token));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theMerchantsOwnCashier_scanningItsQr_isRefusedSelfEarn_andTheQrStaysUsable() {
        LoyaltyUser cashier = user(CASHIER_PHONE);
        authenticate("ROLE_SHOP_ADMIN", MERCHANT, CASHIER_PHONE);

        assertThatThrownBy(() -> qrService.consume(TENANT, consume(cashier)))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode())
                .isEqualTo("SELF_EARN");

        // Nothing earned, and the token was never marked used: the customer the
        // QR was shown to can still scan it.
        verifyNoInteractions(transactionService);
        assertThat(token.getUsedAt()).isNull();
        UUID cashierId = cashier.getId();
        verify(fraud).record(eq(TENANT), eq(cashierId), eq(MERCHANT), any(),
                eq(FraudAttempt.Reason.SELF_EARN), anyString(), any(), any());
    }

    @Test
    void aStaffMemberScanningWithAPlainCustomerToken_isRefusedStaffRecipient() {
        // The same person, on their personal customer session: the token has no
        // merchant claim, so only the staff registry can recognise them.
        LoyaltyUser cashier = user(CASHIER_PHONE);
        authenticate("ROLE_CUSTOMER", null, CASHIER_PHONE);
        when(staffRegistry.isStaffPhone(MERCHANT, CASHIER_PHONE)).thenReturn(true);

        assertThatThrownBy(() -> qrService.consume(TENANT, consume(cashier)))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode())
                .isEqualTo("STAFF_RECIPIENT");

        verifyNoInteractions(transactionService);
        assertThat(token.getUsedAt()).isNull();
    }

    @Test
    void aCustomer_scanningTheQr_stillEarns() {
        LoyaltyUser customer = user(CUSTOMER_PHONE);
        authenticate("ROLE_CUSTOMER", null, CUSTOMER_PHONE);

        qrService.consume(TENANT, consume(customer));

        verify(transactionService).post(eq(TENANT), eq(MERCHANT), any(Dtos.TransactionRequest.class),
                eq(EarnChannel.QR_PRESENCE));
        assertThat(token.getUsedAt()).isNotNull();
    }

    @Test
    void staffOfAnotherMerchant_isJustACustomerHere() {
        // A cashier shopping somewhere else earns like anyone else. The guard is
        // about the QR's OWN merchant, not about holding a staff role.
        LoyaltyUser shopper = user(CUSTOMER_PHONE);
        authenticate("ROLE_SHOP_ADMIN", UUID.randomUUID(), CUSTOMER_PHONE);

        qrService.consume(TENANT, consume(shopper));

        verify(transactionService).post(eq(TENANT), eq(MERCHANT), any(Dtos.TransactionRequest.class),
                eq(EarnChannel.QR_PRESENCE));
    }

    private Dtos.QrConsumeRequest consume(LoyaltyUser who) {
        return new Dtos.QrConsumeRequest(payload.token(), payload.signature(), who.getId(), "ref-1");
    }

    private LoyaltyUser user(String phone) {
        LoyaltyUser u = mock(LoyaltyUser.class);
        UUID id = UUID.randomUUID();
        when(u.getId()).thenReturn(id);
        when(u.getPhoneNumber()).thenReturn(phone);
        when(userService.require(TENANT, id)).thenReturn(u);
        return u;
    }

    private static void authenticate(String role, UUID merchantId, String phone) {
        var auth = new UsernamePasswordAuthenticationToken(
                "caller@test.local", null, List.of(new SimpleGrantedAuthority(role)));
        auth.setDetails(new CallerDetails(merchantId, null, phone, UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // --- Pre-load of the staff registry, before consume() takes the QR lock ---

    @Test
    void prewarm_aLiveMerchantQrOfThisTenant_loadsThatMerchantsStaff() {
        when(qrs.findByToken(token.getToken())).thenReturn(Optional.of(token));

        qrService.prewarmStaffRecipientGuard(TENANT, token.getToken());

        verify(staffRegistry).warm(MERCHANT);
    }

    @Test
    void prewarm_aForeignUsedOrUnknownQr_loadsNothing_andNeverThrows() {
        when(qrs.findByToken("unknown")).thenReturn(Optional.empty());
        qrService.prewarmStaffRecipientGuard(TENANT, "unknown");

        when(qrs.findByToken(token.getToken())).thenReturn(Optional.of(token));
        qrService.prewarmStaffRecipientGuard(UUID.randomUUID(), token.getToken()); // another tenant

        token.setUsedAt(java.time.Instant.now());
        qrService.prewarmStaffRecipientGuard(TENANT, token.getToken()); // already used
        token.setUsedAt(null);

        when(qrs.findByToken("boom")).thenThrow(new IllegalStateException("db down"));
        qrService.prewarmStaffRecipientGuard(TENANT, "boom"); // swallowed

        verify(staffRegistry, never()).warm(any());
        // The refusals (and their fraud evidence) are still consume()'s alone.
        verifyNoInteractions(fraud);
    }
}
