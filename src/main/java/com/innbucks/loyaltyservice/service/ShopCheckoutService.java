package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Orchestrates a single shop checkout against the loyalty ledger: optional
 * earn for the cash portion (PURCHASE) and optional burn for the points
 * portion (REDEMPTION). Both legs commit together under one transaction so
 * partial failures don't leave the customer half-charged.
 *
 * <p>Service-to-service entry point — the public flow at /loyalty/transactions
 * is JWT-gated and merchant-scoped; this one resolves tenant/merchant from the
 * supplied shopId and is reachable only via the X-Internal-Token shared secret.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShopCheckoutService {

    private final ShopRepository shops;
    private final MerchantService merchants;
    private final UserService users;
    private final TransactionService transactionService;
    private final RedemptionService redemptionService;
    private final com.innbucks.loyaltyservice.repository.WalletRepository wallets;
    private final com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics;

    public record Result(UUID shopId,
                         UUID merchantId,
                         UUID tenantId,
                         UUID loyaltyUserId,
                         BigDecimal cashAmount,
                         BigDecimal pointsRedeemed,
                         BigDecimal pointsEarned,
                         BigDecimal walletBalanceAfter,
                         UUID purchaseTransactionId,
                         UUID redemptionTransactionId) {}

    @Transactional
    public Result checkout(UUID shopId,
                           String phoneNumber,
                           BigDecimal cashAmount,
                           BigDecimal pointsAmount,
                           String reference) {
        boolean hasCash = cashAmount != null && cashAmount.signum() > 0;
        boolean hasPoints = pointsAmount != null && pointsAmount.signum() > 0;
        String mode = hasCash && hasPoints ? "mixed" : hasCash ? "cash" : "points";
        try {
            Result r = doCheckout(shopId, phoneNumber, cashAmount, pointsAmount, reference, hasCash, hasPoints);
            metrics.incShopCheckout("success", mode);
            return r;
        } catch (LoyaltyException e) {
            metrics.incShopCheckout("rejected", mode);
            metrics.incShopCheckoutRejected(e.getCode());
            throw e;
        }
    }

    private Result doCheckout(UUID shopId, String phoneNumber, BigDecimal cashAmount,
                              BigDecimal pointsAmount, String reference,
                              boolean hasCash, boolean hasPoints) {
        if (shopId == null) {
            throw LoyaltyException.badRequest("SHOP_REQUIRED", "Please select a shop.");
        }
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw LoyaltyException.badRequest("PHONE_REQUIRED", "Please provide your phone number.");
        }
        if (!hasCash && !hasPoints) {
            throw LoyaltyException.badRequest("AMOUNT_REQUIRED",
                    "at least one of cashAmount or pointsAmount must be positive");
        }

        Shop shop = shops.findById(shopId)
                .orElseThrow(() -> LoyaltyException.notFound("shop"));
        if (shop.getStatus() != Shop.Status.ACTIVE) {
            throw LoyaltyException.badRequest("SHOP_INACTIVE", "This shop isn't accepting purchases right now.");
        }
        UUID tenantId = shop.getTenantId();
        UUID merchantId = shop.getMerchantId();

        Merchant m = merchants.requireMerchant(tenantId, merchantId);
        if (m.getStatus() != Merchant.Status.ACTIVE) {
            throw LoyaltyException.badRequest("MERCHANT_INACTIVE",
                    "merchant is not active; no loyalty operations will run");
        }

        LoyaltyUser user = users.findOrCreatePending(tenantId, phoneNumber, merchantId);

        BigDecimal pointsEarned = BigDecimal.ZERO;
        UUID purchaseTxnId = null;
        BigDecimal balance = walletBalanceOrZero(phoneNumber);

        if (hasCash) {
            Dtos.TransactionRequest earn = new Dtos.TransactionRequest(
                    merchantId, null, phoneNumber, TransactionType.PURCHASE,
                    cashAmount, m.getCurrency(), reference);
            // Attribute the earn to THIS shop. Guest / shop checkout has no JWT
            // for TransactionService to read the shop from, so pass the shopId we
            // already resolved — otherwise the transaction lands with a null shop
            // and the per-shop points report shows nothing.
            Dtos.TransactionResponse earnResp = transactionService.post(tenantId, merchantId, earn, shopId);
            pointsEarned = earnResp.pointsDelta() == null ? BigDecimal.ZERO : earnResp.pointsDelta();
            purchaseTxnId = earnResp.id();
            balance = earnResp.balanceAfter() == null ? balance : earnResp.balanceAfter();
        }

        BigDecimal pointsRedeemed = BigDecimal.ZERO;
        UUID redemptionTxnId = null;
        if (hasPoints) {
            Dtos.RedemptionRequest burn = new Dtos.RedemptionRequest(
                    merchantId, user.getId(), pointsAmount,
                    reference == null ? "shop-checkout" : reference,
                    null);
            RedemptionService.RedemptionResult burnResp =
                    redemptionService.redeemPoints(tenantId, merchantId, burn);
            balance = burnResp.balance();
            redemptionTxnId = burnResp.transactionId();
            pointsRedeemed = pointsAmount;
        }

        log.info("Shop checkout shopId={} merchantId={} phone={} cash={} pointsRedeemed={} pointsEarned={} balance={}",
                shopId, merchantId, MsisdnMasking.mask(phoneNumber), cashAmount, pointsRedeemed, pointsEarned, balance);

        return new Result(shopId, merchantId, tenantId, user.getId(),
                cashAmount == null ? BigDecimal.ZERO : cashAmount,
                pointsRedeemed, pointsEarned, balance,
                purchaseTxnId, redemptionTxnId);
    }

    private BigDecimal walletBalanceOrZero(String phoneNumber) {
        return wallets.findByPhoneNumber(phoneNumber).stream()
                .filter(w -> w.getType() == com.innbucks.loyaltyservice.entity.Wallet.Type.MAIN)
                .map(w -> w.getBalance() == null ? BigDecimal.ZERO : w.getBalance())
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }
}
