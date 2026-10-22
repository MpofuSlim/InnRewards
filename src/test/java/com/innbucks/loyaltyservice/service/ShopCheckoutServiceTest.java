package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShopCheckoutService}. The S2S checkout spends points
 * and refuses a cash leg (CASH_EARN_NOT_ACCEPTED). An earn on a channel that
 * earns is posted with the server-resolved {@code shopId}: the checkout has no
 * JWT, so {@code TransactionService} would otherwise stamp the shop from the
 * absent token and the per-shop points report would miss the row.
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
    void checkout_s2sCash_isRefused_beforeAnythingIsReadOrWritten() {
        // payment-service's S2S checkout carries a cash amount the customer typed:
        // nothing collected it and no till recorded the sale. It must never earn,
        // and the refusal comes before the shop, the customer or the wallet is read.
        assertThatThrownBy(() -> service.checkout(UUID.randomUUID(), "+263782606983",
                new BigDecimal("5"), null, "SHOP-1"))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode())
                        .isEqualTo("CASH_EARN_NOT_ACCEPTED"));

        verifyNoInteractions(shops, merchants, users, transactionService, redemptionService);
    }

    @Test
    void checkout_s2sCashAndPoints_isRefused_andBurnsNothing() {
        assertThatThrownBy(() -> service.checkout(UUID.randomUUID(), "+263782606983",
                new BigDecimal("5"), new BigDecimal("30"), "SHOP-2"))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode())
                        .isEqualTo("CASH_EARN_NOT_ACCEPTED"));

        verifyNoInteractions(transactionService, redemptionService);
    }

    @Test
    void checkout_s2sPointsOnly_burnsAndNeverEarns() {
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
        when(redemptionService.redeemPoints(eq(tenantId), eq(merchantId), any(Dtos.RedemptionRequest.class)))
                .thenReturn(new RedemptionService.RedemptionResult(UUID.randomUUID(), new BigDecimal("70")));

        ShopCheckoutService.Result result = service.checkout(shopId, phone, null, new BigDecimal("30"), "SHOP-3");

        assertThat(result.pointsRedeemed()).isEqualByComparingTo("30");
        assertThat(result.pointsEarned()).isEqualByComparingTo("0");
        verify(transactionService, never()).post(any(), any(), any(Dtos.TransactionRequest.class), any(),
                any(com.innbucks.loyaltyservice.entity.EarnChannel.class));
    }

    @Test
    void checkout_atTheTill_postsTheEarnAsTypedPhone_soTheStaffGuardsRun() {
        // The till's guest checkout is a cashier typing a phone number. Posting
        // it as CHECKOUT_S2S exempted it from SELF_EARN / STAFF_RECIPIENT, so a
        // cashier could earn every unclaimed sale to their own phone. The channel
        // the caller passes is the channel the earn is posted with.
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        String phone = "+263782606983";

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
        when(transactionService.post(eq(tenantId), eq(merchantId), any(Dtos.TransactionRequest.class), eq(shopId),
                eq(com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE)))
                .thenReturn(new Dtos.TransactionResponse(
                        UUID.randomUUID(), TransactionType.PURCHASE, new BigDecimal("5"),
                        new BigDecimal("5"), new BigDecimal("20"), null, null, shopId,
                        null, com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE, "SHOP-1", null,
                        null, "USD", new BigDecimal("5")));

        service.checkout(shopId, phone, new BigDecimal("5"), BigDecimal.ZERO, "SHOP-1",
                com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE);

        verify(transactionService).post(eq(tenantId), eq(merchantId),
                any(Dtos.TransactionRequest.class), eq(shopId),
                eq(com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE));
    }

    /**
     * A mixed cash-and-points checkout on a channel that earns (the till's
     * TYPED_PHONE). The earn (PURCHASE) and burn (REDEMPTION) legs must carry
     * DISTINCT references, or they collide on the (merchant, reference) partial
     * unique index (V16) and the whole checkout 500s. Regression guard for the
     * arg-swap: the reference must land in the REFERENCE field (not the reason)
     * and be ":redeem"-suffixed so it differs from the earn leg's raw reference.
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
                null, com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE, reference, null, null,
                "USD", new BigDecimal("5"));
        when(transactionService.post(eq(tenantId), eq(merchantId), any(Dtos.TransactionRequest.class), eq(shopId),
                eq(com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE)))
                .thenReturn(earnResp);
        when(redemptionService.redeemPoints(eq(tenantId), eq(merchantId), any(Dtos.RedemptionRequest.class)))
                .thenReturn(new RedemptionService.RedemptionResult(UUID.randomUUID(), new BigDecimal("10")));

        service.checkout(shopId, phone, new BigDecimal("5"), new BigDecimal("30"), reference,
                com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE);

        // The earn leg carries the RAW reference...
        ArgumentCaptor<Dtos.TransactionRequest> earnCap = ArgumentCaptor.forClass(Dtos.TransactionRequest.class);
        verify(transactionService).post(eq(tenantId), eq(merchantId), earnCap.capture(), eq(shopId),
                eq(com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE));
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
