package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.EarnChannel;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ShopCheckoutService#tillCheckout}: the till's checkout where the
 * customer pays part in cash and part with points. Pins the order (burn before
 * earn), that the burn runs the caller-enforced redeem path, the per-leg
 * references, and the replay on a resent reference.
 */
@ExtendWith(MockitoExtension.class)
class TillCheckoutTest {

    @Mock private ShopRepository shops;
    @Mock private MerchantService merchants;
    @Mock private UserService users;
    @Mock private TransactionService transactionService;
    @Mock private RedemptionService redemptionService;
    @Mock private WalletRepository wallets;
    @Mock private LoyaltyMetrics metrics;
    @Mock private LoyaltyTransactionRepository transactions;

    @InjectMocks private ShopCheckoutService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private final UUID shopId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final String phone = "+263771234567";
    private final String ref = "POS-0042-0117";

    @BeforeEach
    void outlet() {
        Shop shop = new Shop();
        shop.setId(shopId);
        shop.setTenantId(tenantId);
        shop.setMerchantId(merchantId);
        lenient().when(shops.findById(shopId)).thenReturn(Optional.of(shop));
        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setTenantId(tenantId);
        merchant.setCurrency("USD");
        lenient().when(merchants.requireMerchant(tenantId, merchantId)).thenReturn(merchant);
    }

    @Test
    void cashAndPoints_burnsFirst_thenEarnsOnTheCash_withDistinctReferences() {
        UUID burnId = stubBurn("500", "5.00", "1000");
        LoyaltyUser customer = customer();
        when(users.findOrCreatePending(tenantId, phone, merchantId)).thenReturn(customer);
        UUID earnId = stubEarn(TransactionType.PURCHASE, "12.00", "24", "1024");

        ShopCheckoutService.TillResult t = service.tillCheckout(tenantId, shopId, phone,
                new BigDecimal("12.00"), new BigDecimal("5.00"), null, null, ref);

        // Points first: this sale's own earn must never fund its points part.
        InOrder order = inOrder(redemptionService, transactionService);
        ArgumentCaptor<Dtos.RedemptionRequest> burn = ArgumentCaptor.forClass(Dtos.RedemptionRequest.class);
        order.verify(redemptionService).redeemPoints(eq(tenantId), eq(merchantId), burn.capture(), eq(true));
        ArgumentCaptor<Dtos.TransactionRequest> earn = ArgumentCaptor.forClass(Dtos.TransactionRequest.class);
        order.verify(transactionService).post(eq(tenantId), eq(merchantId), earn.capture(), eq(shopId),
                eq(EarnChannel.TYPED_PHONE));

        assertThat(burn.getValue().assigneePhone()).isEqualTo(phone);
        assertThat(burn.getValue().userId()).isNull();
        assertThat(burn.getValue().amount()).isEqualByComparingTo("5.00");
        assertThat(burn.getValue().points()).isNull();
        assertThat(burn.getValue().currency()).isEqualTo("USD");
        assertThat(burn.getValue().reference()).isEqualTo(ref + ":redeem");
        assertThat(earn.getValue().reference()).isEqualTo(ref);
        assertThat(earn.getValue().type()).isEqualTo(TransactionType.PURCHASE);
        assertThat(earn.getValue().amount()).isEqualByComparingTo("12.00");

        ShopCheckoutService.Result r = t.result();
        assertThat(t.replayed()).isFalse();
        assertThat(t.pointsValue()).isEqualByComparingTo("5.00");
        assertThat(t.currency()).isEqualTo("USD");
        assertThat(r.pointsRedeemed()).isEqualByComparingTo("500");
        assertThat(r.pointsEarned()).isEqualByComparingTo("24");
        assertThat(r.walletBalanceAfter()).isEqualByComparingTo("1024");
        assertThat(r.redemptionTransactionId()).isEqualTo(burnId);
        assertThat(r.purchaseTransactionId()).isEqualTo(earnId);
        assertThat(r.loyaltyUserId()).isEqualTo(customerId);
    }

    @Test
    void theBurnRow_isAttributedToThisShop_whenTheCallerCarriesNoShop() {
        stubBurn("500", "5.00", "1000");

        ShopCheckoutService.TillResult t = service.tillCheckout(tenantId, shopId, phone,
                null, new BigDecimal("5.00"), null, null, ref);

        LoyaltyTransaction burned = transactions.findById(t.result().redemptionTransactionId()).orElseThrow();
        assertThat(burned.getShopId()).isEqualTo(shopId);
    }

    @Test
    void pointsOnly_neverEarns_andReportsTheBurnBalance() {
        stubBurn("500", "5.00", "1000");

        ShopCheckoutService.TillResult t = service.tillCheckout(tenantId, shopId, phone,
                BigDecimal.ZERO, new BigDecimal("5.00"), null, null, ref);

        verify(transactionService, never()).post(any(), any(), any(), any(), any());
        verify(users, never()).findOrCreatePending(any(), any(), any());
        assertThat(t.result().cashAmount()).isEqualByComparingTo("0");
        assertThat(t.result().walletBalanceAfter()).isEqualByComparingTo("1000");
        assertThat(t.result().purchaseTransactionId()).isNull();
    }

    @Test
    void cashByCard_earnsAsCardPayment_andBurnsNothing() {
        when(users.findOrCreatePending(tenantId, phone, merchantId)).thenReturn(customer());
        stubEarn(TransactionType.CARD_PAYMENT, "15.00", "30", "30");

        ShopCheckoutService.TillResult t = service.tillCheckout(tenantId, shopId, phone,
                new BigDecimal("15.00"), null, null, TransactionType.CARD_PAYMENT, ref);

        ArgumentCaptor<Dtos.TransactionRequest> earn = ArgumentCaptor.forClass(Dtos.TransactionRequest.class);
        verify(transactionService).post(eq(tenantId), eq(merchantId), earn.capture(), eq(shopId),
                eq(EarnChannel.TYPED_PHONE));
        assertThat(earn.getValue().type()).isEqualTo(TransactionType.CARD_PAYMENT);
        verify(redemptionService, never()).redeemPoints(any(), any(), any(), anyBoolean());
        assertThat(t.pointsValue()).isEqualByComparingTo("0");
        assertThat(t.result().redemptionTransactionId()).isNull();
    }

    @Test
    void aZeroPointEarn_doesNotReportABalanceOfZero() {
        // An earn below the minimum answers balanceAfter 0, which is not the
        // customer's balance; the checkout reads the wallet instead.
        stubBurn("500", "5.00", "1000");
        when(users.findOrCreatePending(tenantId, phone, merchantId)).thenReturn(customer());
        stubEarn(TransactionType.PURCHASE, "4.00", "0", "0");

        ShopCheckoutService.TillResult t = service.tillCheckout(tenantId, shopId, phone,
                new BigDecimal("4.00"), new BigDecimal("5.00"), null, null, ref);

        assertThat(t.result().pointsEarned()).isEqualByComparingTo("0");
        assertThat(t.result().walletBalanceAfter()).isEqualByComparingTo("1000");
    }

    @Test
    void aResentReference_forTheSameCustomer_replaysAndMovesNothing() {
        LoyaltyTransaction earned = row(TransactionType.PURCHASE, customerId, "12.00", "24");
        LoyaltyTransaction burned = row(TransactionType.REDEMPTION, customerId, "5.00", "-500");
        when(transactions.findFirstByMerchantIdAndReference(merchantId, ref)).thenReturn(Optional.of(earned));
        when(transactions.findFirstByMerchantIdAndReference(merchantId, ref + ":redeem"))
                .thenReturn(Optional.of(burned));
        when(users.findByPhone(tenantId, phone)).thenReturn(Optional.of(customer()));

        ShopCheckoutService.TillResult t = service.tillCheckout(tenantId, shopId, phone,
                new BigDecimal("12.00"), new BigDecimal("5.00"), null, null, ref);

        assertThat(t.replayed()).isTrue();
        assertThat(t.result().pointsRedeemed()).isEqualByComparingTo("500");
        assertThat(t.result().pointsEarned()).isEqualByComparingTo("24");
        assertThat(t.pointsValue()).isEqualByComparingTo("5.00");
        assertThat(t.result().purchaseTransactionId()).isEqualTo(earned.getId());
        assertThat(t.result().redemptionTransactionId()).isEqualTo(burned.getId());
        verify(redemptionService, never()).redeemPoints(any(), any(), any(), anyBoolean());
        verify(transactionService, never()).post(any(), any(), any(), any(), any());
    }

    @Test
    void aResentReference_forAnotherCustomer_isRefused() {
        LoyaltyTransaction someoneElses = row(TransactionType.PURCHASE, UUID.randomUUID(), "12.00", "24");
        when(transactions.findFirstByMerchantIdAndReference(merchantId, ref)).thenReturn(Optional.of(someoneElses));
        when(users.findByPhone(tenantId, phone)).thenReturn(Optional.of(customer()));

        assertThatThrownBy(() -> service.tillCheckout(tenantId, shopId, phone,
                new BigDecimal("12.00"), null, null, null, ref))
                .isInstanceOf(LoyaltyException.class)
                .extracting("code").isEqualTo("DUPLICATE_REFERENCE");
        verify(transactionService, never()).post(any(), any(), any(), any(), any());
    }

    @Test
    void anEarnTypeOtherThanPurchaseOrCard_isRefusedBeforeAnythingMoves() {
        assertThatThrownBy(() -> service.tillCheckout(tenantId, shopId, phone,
                new BigDecimal("12.00"), null, null, TransactionType.ADJUSTMENT, ref))
                .isInstanceOf(LoyaltyException.class)
                .extracting("code").isEqualTo("BAD_TRANSACTION_TYPE");
        verify(transactionService, never()).post(any(), any(), any(), any(), any());
    }

    @Test
    void aShopOfAnotherTenant_isNotFound() {
        UUID otherTenant = UUID.randomUUID();
        assertThatThrownBy(() -> service.tillCheckout(otherTenant, shopId, phone,
                new BigDecimal("12.00"), null, null, null, ref))
                .isInstanceOf(LoyaltyException.class)
                .extracting("code").isEqualTo("404 NOT_FOUND");
        verify(merchants, never()).requireMerchant(any(), any());
    }

    private LoyaltyUser customer() {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(customerId);
        u.setPhoneNumber(phone);
        return u;
    }

    private UUID stubBurn(String points, String value, String balanceAfter) {
        LoyaltyTransaction row = row(TransactionType.REDEMPTION, customerId, value, "-" + points);
        when(redemptionService.redeemPoints(eq(tenantId), eq(merchantId), any(Dtos.RedemptionRequest.class), eq(true)))
                .thenReturn(new RedemptionService.RedemptionResult(row.getId(), new BigDecimal(balanceAfter)));
        when(transactions.findById(row.getId())).thenReturn(Optional.of(row));
        return row.getId();
    }

    private UUID stubEarn(TransactionType type, String amount, String points, String balanceAfter) {
        UUID id = UUID.randomUUID();
        when(transactionService.post(eq(tenantId), eq(merchantId), any(Dtos.TransactionRequest.class), eq(shopId),
                eq(EarnChannel.TYPED_PHONE)))
                .thenReturn(new Dtos.TransactionResponse(id, type, new BigDecimal(amount),
                        new BigDecimal(points), new BigDecimal(balanceAfter), null, null, shopId,
                        null, EarnChannel.TYPED_PHONE, ref, null, null, "USD", new BigDecimal(amount)));
        return id;
    }

    private static LoyaltyTransaction row(TransactionType type, UUID userId, String amount, String delta) {
        LoyaltyTransaction t = new LoyaltyTransaction();
        t.setId(UUID.randomUUID());
        t.setType(type);
        t.setUserId(userId);
        t.setAmount(new BigDecimal(amount));
        t.setPointsDelta(new BigDecimal(delta));
        t.setCurrency("USD");
        return t;
    }
}
