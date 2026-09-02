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
import org.mockito.ArgumentCaptor;
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
                new BigDecimal("5"), new BigDecimal("20"), null, null, shopId,
                null, com.innbucks.loyaltyservice.entity.EarnChannel.CHECKOUT_S2S, "ref", null,
                null, "USD", new BigDecimal("5"));
        when(transactionService.post(eq(tenantId), eq(merchantId), any(Dtos.TransactionRequest.class), eq(shopId),
                eq(com.innbucks.loyaltyservice.entity.EarnChannel.CHECKOUT_S2S)))
                .thenReturn(earnResp);

        ShopCheckoutService.Result result =
                service.checkout(shopId, phone, new BigDecimal("5"), null, "ref");

        // The earn MUST be posted with THIS shopId (the bug: it went through the
        // JWT-derived overload with no JWT, so the transaction had a null shop
        // and the per-shop points report showed 0).
        verify(transactionService).post(eq(tenantId), eq(merchantId),
                any(Dtos.TransactionRequest.class), eq(shopId),
                eq(com.innbucks.loyaltyservice.entity.EarnChannel.CHECKOUT_S2S));
        assertThat(result.shopId()).isEqualTo(shopId);
        assertThat(result.pointsEarned()).isEqualByComparingTo("5");
    }

    /**
     * The flagship mixed CASH_AND_POINTS flow. The earn (PURCHASE) and burn
     * (REDEMPTION) legs must carry DISTINCT references, or they collide on the
     * (merchant, reference) partial unique index (V16) and the whole checkout
     * 500s. Regression guard for the arg-swap: the reference must land in the
     * REFERENCE field (not the reason) and be ":redeem"-suffixed so it differs
     * from the earn leg's raw reference.
     */
    @Test
    void checkout_mixedCashAndPoints_burnUsesDistinctRedeemReference() {
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        String phone = "+263782606983";
        String reference = "SHOP-abc123";

        Shop shop = new Shop();
        shop.setId(shopId);
        shop.setTenantId(tenantId);
        shop.setMerchantId(merchantId);
        when(shops.findById(shopId)).thenReturn(Optional.of(shop));

        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setTenantId(tenantId);
        merchant.setCurrency("USD");
        when(merchants.requireMerchant(tenantId, merchantId)).thenReturn(merchant);

        LoyaltyUser user = new LoyaltyUser();
        user.setId(UUID.randomUUID());
        when(users.findOrCreatePending(tenantId, phone, merchantId)).thenReturn(user);

        Dtos.TransactionResponse earnResp = new Dtos.TransactionResponse(
                UUID.randomUUID(), TransactionType.PURCHASE, new BigDecimal("5"),
                new BigDecimal("5"), new BigDecimal("20"), null, null, shopId,
                null, com.innbucks.loyaltyservice.entity.EarnChannel.CHECKOUT_S2S, reference, null, null,
                "USD", new BigDecimal("5"));
        when(transactionService.post(eq(tenantId), eq(merchantId), any(Dtos.TransactionRequest.class), eq(shopId),
                eq(com.innbucks.loyaltyservice.entity.EarnChannel.CHECKOUT_S2S)))
                .thenReturn(earnResp);
        when(redemptionService.redeemPoints(eq(tenantId), eq(merchantId), any(Dtos.RedemptionRequest.class)))
                .thenReturn(new RedemptionService.RedemptionResult(UUID.randomUUID(), new BigDecimal("10")));

        service.checkout(shopId, phone, new BigDecimal("5"), new BigDecimal("30"), reference);

        // The earn leg carries the RAW reference...
        ArgumentCaptor<Dtos.TransactionRequest> earnCap = ArgumentCaptor.forClass(Dtos.TransactionRequest.class);
        verify(transactionService).post(eq(tenantId), eq(merchantId), earnCap.capture(), eq(shopId),
                eq(com.innbucks.loyaltyservice.entity.EarnChannel.CHECKOUT_S2S));
        assertThat(earnCap.getValue().reference()).isEqualTo(reference);

        // ...and the burn leg carries a DISTINCT ":redeem"-suffixed reference, in
        // the REFERENCE field (not smuggled into the reason).
        ArgumentCaptor<Dtos.RedemptionRequest> burnCap = ArgumentCaptor.forClass(Dtos.RedemptionRequest.class);
        verify(redemptionService).redeemPoints(eq(tenantId), eq(merchantId), burnCap.capture());
        Dtos.RedemptionRequest burn = burnCap.getValue();
        assertThat(burn.reference()).isEqualTo(reference + ":redeem");
        assertThat(burn.reference()).isNotEqualTo(earnCap.getValue().reference()); // no collision
        assertThat(burn.points()).isEqualByComparingTo("30");
    }
}
