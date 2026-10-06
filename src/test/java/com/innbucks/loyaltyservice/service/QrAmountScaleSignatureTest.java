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
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@code qr_tokens.amount} is NUMERIC(19,4) and the QR signature covers the
 * amount's plain string. An amount sent as {@code 40.00} was signed as "40.00"
 * and read back at consume as "40.0000", so every such QR was refused
 * 403 BAD_SIGNATURE. Issue now signs the amount at the column's scale.
 */
class QrAmountScaleSignatureTest {

    private final QrTokenRepository qrs = mock(QrTokenRepository.class);
    private final TransactionService transactionService = mock(TransactionService.class);
    private final UserService userService = mock(UserService.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);

    private final QrService qrService = new QrService(
            qrs, transactionService, mock(TransferService.class), mock(FraudService.class), userService,
            new MerchantAuthz(merchants, mock(ShopRepository.class)), mock(StaffRegistry.class),
            new LoyaltyProperties(null, new LoyaltyProperties.Qr(
                    "unit-test-qr-secret-unit-test-qr-secret-unit-test", 300), null, null, null, null, null),
            new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD"));

    private final UUID tenantId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String role, String phone) {
        var auth = new UsernamePasswordAuthenticationToken(
                "caller@test.local", null, List.of(new SimpleGrantedAuthority(role)));
        auth.setDetails(new CallerDetails(null, null, phone, UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private QrToken issue(String amount) {
        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setTenantId(tenantId);
        when(merchants.findById(merchantId)).thenReturn(Optional.of(merchant));
        authenticate("ROLE_SUPER_ADMIN", null);
        qrService.issue(tenantId, new Dtos.QrIssueRequest(QrToken.SourceType.MERCHANT, merchantId,
                TransactionType.QR_PAY, new BigDecimal(amount), "USD", null));
        ArgumentCaptor<QrToken> saved = ArgumentCaptor.forClass(QrToken.class);
        verify(qrs).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void aTwoDecimalAmount_isStoredAndSignedAtTheColumnScale() {
        QrToken q = issue("40.00");
        assertThat(q.getAmount()).isEqualByComparingTo("40");
        assertThat(q.getAmount().scale()).isEqualTo(4);
    }

    @Test
    void theQrStillVerifies_afterTheDatabaseHandsTheAmountBackAtScaleFour() {
        QrToken issued = issue("40.00");

        // What consume loads: the same row, amount as NUMERIC(19,4) returns it.
        QrToken reloaded = new QrToken();
        reloaded.setTenantId(issued.getTenantId());
        reloaded.setSourceType(issued.getSourceType());
        reloaded.setSourceId(issued.getSourceId());
        reloaded.setTransactionType(issued.getTransactionType());
        reloaded.setAmount(new BigDecimal("40.0000"));
        reloaded.setCurrency(issued.getCurrency());
        reloaded.setExpiresAt(issued.getExpiresAt());
        reloaded.setToken(issued.getToken());
        reloaded.setSignature(issued.getSignature());
        when(qrs.lockByToken(issued.getToken())).thenReturn(Optional.of(reloaded));

        UUID customerId = UUID.randomUUID();
        LoyaltyUser customer = new LoyaltyUser();
        customer.setId(customerId);
        customer.setTenantId(tenantId);
        customer.setPhoneNumber("+263771234567");
        when(userService.require(tenantId, customerId)).thenReturn(customer);
        Dtos.TransactionResponse earned = mock(Dtos.TransactionResponse.class);
        when(transactionService.postForShop(eq(tenantId), eq(merchantId), any(), any(), any()))
                .thenReturn(earned);

        authenticate("ROLE_CUSTOMER", "+263771234567");
        qrService.consume(tenantId, new Dtos.QrConsumeRequest(
                issued.getToken(), issued.getSignature(), customerId, "POS-1"));

        verify(transactionService).postForShop(eq(tenantId), eq(merchantId), any(), any(), any());
        assertThat(reloaded.getUsedAt()).isNotNull();
    }

    @Test
    void moreThanFourDecimals_isRefused_ratherThanSignedAndThenRounded() {
        assertThatThrownBy(() -> QrService.atColumnScale(new BigDecimal("1.00005")))
                .isInstanceOf(LoyaltyException.class)
                .hasMessage("amount can have at most 4 decimal places");
        assertThat(QrService.atColumnScale(null)).isNull();
    }
}
