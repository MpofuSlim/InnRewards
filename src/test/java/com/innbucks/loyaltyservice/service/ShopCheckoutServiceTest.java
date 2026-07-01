package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShopCheckoutService}. Pins the fix for the per-shop
 * points report reading 0: guest / shop checkout has no JWT, so the earn must
 * be posted with the server-resolved {@code shopId} — otherwise
 * {@code TransactionService} would stamp the shop from the (absent) JWT and the
 * transaction would land with a null shop, invisible to the per-shop report.
 */
@ExtendWith(MockitoExtension.class)
class ShopCheckoutServiceTest {

    @Mock private ShopRepository shops;
    @Mock private MerchantService merchants;
    @Mock private UserService users;
    @Mock private TransactionService transactionService;
    @Mock private RedemptionService redemptionService;
    @Mock private WalletRepository wallets;
    @Mock private LoyaltyMetrics metrics;

    @InjectMocks private ShopCheckoutService service;

    @Test
    void checkout_cashEarn_attributesTheEarnToTheShop() {
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        String phone = "+263782606983";

        Shop shop = new Shop();
        shop.setId(shopId);
        shop.setTenantId(tenantId);
        shop.setMerchantId(merchantId);   // status defaults to ACTIVE
        when(shops.findById(shopId)).thenReturn(Optional.of(shop));

        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setTenantId(tenantId);
        merchant.setCurrency("USD");       // status defaults to ACTIVE
        when(merchants.requireMerchant(tenantId, merchantId)).thenReturn(merchant);

        LoyaltyUser user = new LoyaltyUser();
        user.setId(UUID.randomUUID());
        when(users.findOrCreatePending(tenantId, phone, merchantId)).thenReturn(user);

        Dtos.TransactionResponse earnResp = new Dtos.TransactionResponse(
                UUID.randomUUID(), TransactionType.PURCHASE, new BigDecimal("5"),
                new BigDecimal("5"), new BigDecimal("20"), null, null, shopId, "ref", null);
        when(transactionService.post(eq(tenantId), eq(merchantId), any(Dtos.TransactionRequest.class), eq(shopId)))
                .thenReturn(earnResp);

        ShopCheckoutService.Result result =
                service.checkout(shopId, phone, new BigDecimal("5"), null, "ref");

        // The earn MUST be posted with THIS shopId (the bug: it went through the
        // JWT-derived overload with no JWT, so the transaction had a null shop
        // and the per-shop points report showed 0).
        verify(transactionService).post(eq(tenantId), eq(merchantId),
                any(Dtos.TransactionRequest.class), eq(shopId));
        assertThat(result.shopId()).isEqualTo(shopId);
        assertThat(result.pointsEarned()).isEqualByComparingTo("5");
    }
}
