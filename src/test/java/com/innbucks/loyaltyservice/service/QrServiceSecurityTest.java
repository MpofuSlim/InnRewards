package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.QrToken;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.QrTokenRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
import static org.mockito.Mockito.*;

/**
 * Access-control regression tests for {@link QrService} (OWASP A01 / A04).
 *
 * <p>Pins the fix for the loyalty point-minting hole: previously a CUSTOMER could
 * self-issue a MERCHANT-sourced QR for any merchant + amount and then consume it
 * to credit points to themselves, because {@code issue()} trusted the request's
 * {@code sourceType}/{@code sourceId} and {@code consume()} trusted the request's
 * {@code userId}. These tests prove:
 * <ul>
 *   <li>a caller who does not administer the merchant cannot issue a MERCHANT QR,</li>
 *   <li>a caller cannot issue a P2P (USER) QR draining a wallet that isn't theirs,</li>
 *   <li>consume rejects a {@code userId} that isn't the caller BEFORE touching the
 *       token — no state is read or mutated.</li>
 * </ul>
 * Each test also asserts no persistence side effect fired (the guard runs first).
 */
class QrServiceSecurityTest {

    private final QrTokenRepository qrs = mock(QrTokenRepository.class);
    private final TransactionService transactionService = mock(TransactionService.class);
    private final TransferService transferService = mock(TransferService.class);
    private final FraudService fraud = mock(FraudService.class);
    private final UserService userService = mock(UserService.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);

    private final MerchantAuthz merchantAuthz = new MerchantAuthz(merchants);

    private final LoyaltyProperties props =
            new LoyaltyProperties(null, new LoyaltyProperties.Qr(
                    "unit-test-qr-secret-unit-test-qr-secret-unit-test", 300), null, null);

    private final QrService qrService = new QrService(
            qrs, transactionService, transferService, fraud, userService, merchantAuthz, props);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticates a CUSTOMER with the given email + phone and NO merchant/shop scope. */
    private void authenticateCustomer(String email, String phone) {
        var auth = new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        auth.setDetails(new CallerDetails(null, null, phone, UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void issue_merchantQr_byNonOwner_isForbidden_andPersistsNothing() {
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setTenantId(tenantId);
        merchant.setAdminEmail("owner@merchant.test");   // owned by someone else
        when(merchants.findById(merchantId)).thenReturn(Optional.of(merchant));

        authenticateCustomer("attacker@evil.test", "+263771234567");

        Dtos.QrIssueRequest req = new Dtos.QrIssueRequest(
                QrToken.SourceType.MERCHANT, merchantId, TransactionType.QR_PAY,
                new BigDecimal("1000000.00"), "USD", null);

        assertThatThrownBy(() -> qrService.issue(tenantId, req))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> {
                    LoyaltyException le = (LoyaltyException) ex;
                    assertThat(le.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(le.getCode()).isEqualTo("NOT_MERCHANT_OWNER");
                });

        verify(qrs, never()).save(any());
    }

    @Test
    void issue_userQr_forSomeoneElsesWallet_isForbidden_andPersistsNothing() {
        UUID tenantId = UUID.randomUUID();
        UUID victimUserId = UUID.randomUUID();

        LoyaltyUser victim = mock(LoyaltyUser.class);
        when(userService.require(tenantId, victimUserId)).thenReturn(victim);
        // Strict ownership fails: the sender wallet isn't the caller's.
        doThrow(LoyaltyException.forbidden("NOT_WALLET_OWNER",
                "you can only act on your own loyalty account"))
                .when(userService).requireCallerOwns(victim);

        authenticateCustomer("attacker@evil.test", "+263771234567");

        Dtos.QrIssueRequest req = new Dtos.QrIssueRequest(
                QrToken.SourceType.USER, victimUserId, TransactionType.TRANSFER,
                new BigDecimal("500.00"), "USD", null);

        assertThatThrownBy(() -> qrService.issue(tenantId, req))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(qrs, never()).save(any());
    }

    @Test
    void consume_withForeignUserId_isRejectedBeforeTokenIsRead() {
        UUID tenantId = UUID.randomUUID();
        UUID victimUserId = UUID.randomUUID();

        LoyaltyUser victim = mock(LoyaltyUser.class);
        when(userService.require(tenantId, victimUserId)).thenReturn(victim);
        doThrow(LoyaltyException.forbidden("NOT_WALLET_OWNER",
                "you can only act on your own loyalty account"))
                .when(userService).requireCallerOwns(victim);

        authenticateCustomer("attacker@evil.test", "+263771234567");

        Dtos.QrConsumeRequest req = new Dtos.QrConsumeRequest(
                "qr_some_token", "deadbeefsignature", victimUserId, "ref");

        assertThatThrownBy(() -> qrService.consume(tenantId, req))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        // Guard runs first: the token is never looked up, nothing is posted.
        verify(qrs, never()).lockByToken(any());
        verifyNoInteractions(transactionService, transferService);
    }
}
