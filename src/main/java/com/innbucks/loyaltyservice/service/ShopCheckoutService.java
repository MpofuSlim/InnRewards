package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
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
    private final LoyaltyTransactionRepository transactions;

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

    /** The payment-service S2S checkout: the customer's phone comes from their
     *  own payment, so no staff member chose the recipient. */
    /**
     * Pre-load the STAFF_RECIPIENT registry for the shop's merchant before the
     * checkout's transaction opens — see
     * {@link TransactionService#prewarmStaffRecipientGuard}. Never throws.
     */
    public void prewarmStaffRecipientGuard(UUID tenantId, UUID merchantId) {
        transactionService.prewarmStaffRecipientGuard(tenantId, merchantId);
    }

    @Transactional
    public Result checkout(UUID shopId,
                           String phoneNumber,
                           BigDecimal cashAmount,
                           BigDecimal pointsAmount,
                           String reference) {
        return checkout(shopId, phoneNumber, cashAmount, pointsAmount, reference,
                com.innbucks.loyaltyservice.entity.EarnChannel.CHECKOUT_S2S);
    }

    /**
     * @param earnChannel how the recipient was chosen, and therefore which
     *        earn-integrity guards apply. The till's guest checkout passes
     *        {@code TYPED_PHONE}: a cashier keys in the phone, which is exactly
     *        the shape {@code SELF_EARN} / {@code STAFF_RECIPIENT} exist for.
     *        Labelling it {@code CHECKOUT_S2S} exempted it from both, so a
     *        cashier could ring every unclaimed sale up to their own phone.
     */
    @Transactional
    public Result checkout(UUID shopId,
                           String phoneNumber,
                           BigDecimal cashAmount,
                           BigDecimal pointsAmount,
                           String reference,
                           com.innbucks.loyaltyservice.entity.EarnChannel earnChannel) {
        boolean hasCash = cashAmount != null && cashAmount.signum() > 0;
        boolean hasPoints = pointsAmount != null && pointsAmount.signum() > 0;
        String mode = hasCash && hasPoints ? "mixed" : hasCash ? "cash" : "points";
        try {
            Result r = doCheckout(shopId, phoneNumber, cashAmount, pointsAmount, reference, hasCash, hasPoints,
                    earnChannel);
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
                              boolean hasCash, boolean hasPoints,
                              com.innbucks.loyaltyservice.entity.EarnChannel earnChannel) {
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
            Dtos.TransactionResponse earnResp = transactionService.post(tenantId, merchantId, earn, shopId,
                    earnChannel);
            pointsEarned = earnResp.pointsDelta() == null ? BigDecimal.ZERO : earnResp.pointsDelta();
            purchaseTxnId = earnResp.id();
            balance = earnResp.balanceAfter() == null ? balance : earnResp.balanceAfter();
        }

        BigDecimal pointsRedeemed = BigDecimal.ZERO;
        UUID redemptionTxnId = null;
        if (hasPoints) {
            // Distinct reference per leg. The earn (PURCHASE) leg above stores the
            // raw `reference`; the burn (REDEMPTION) leg must NOT store the same
            // value, or the two rows collide on the (merchant, reference) partial
            // unique index (V16, which covers both types) and the whole mixed
            // checkout dies with a DataIntegrityViolation. Suffix with ":redeem",
            // exactly as TicketingLoyaltyService does for the same earn+burn pair.
            //
            // Just as important: the reference goes in the REFERENCE field (5th
            // arg — the idempotency key), NOT the reason (4th). Passing it as the
            // reason was the original bug: with reference=null, RedemptionService
            // stored the reason AS the reference, reproducing the earn leg's value
            // and both 500'ing the flow AND silently disabling burn-leg
            // idempotency. "shop-checkout" is only a readable fallback for the
            // reference==null case.
            String redeemRef = reference == null ? null : reference + ":redeem";
            Dtos.RedemptionRequest burn = new Dtos.RedemptionRequest(
                    merchantId, user.getId(), pointsAmount,
                    "shop-checkout",
                    redeemRef);
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

    /** Suffix that keeps the burn leg's reference distinct from the earn leg's (V16 index). */
    static final String REDEEM_SUFFIX = ":redeem";

    /**
     * What a till checkout did. {@code pointsValue} is the bill the points
     * covered, in {@code currency}; {@code replayed} is true when the reference
     * had already been checked out and nothing new was written.
     */
    public record TillResult(Result result, BigDecimal pointsValue, String currency, boolean replayed) {}

    /**
     * The till's checkout: the customer pays part of the bill in cash (or card)
     * and part with points, in one call. Either leg may be absent.
     *
     * <p>The points leg is burned FIRST, so points earned on this sale's cash
     * can never pay for this same sale. It goes through
     * {@link RedemptionService#redeemPoints} with caller enforcement, so it runs
     * exactly the checks of {@code POST /loyalty/redeem}: an existing account
     * (a burn never creates one), the cashier's merchant, the spend gate, the
     * balance. The cash leg then earns as the guest checkout always has
     * ({@code TYPED_PHONE}, so SELF_EARN / STAFF_RECIPIENT apply). Both legs
     * share this transaction: a refusal of either writes nothing.
     *
     * <p>{@code reference} is the idempotency key. A retry with the same
     * reference and the same customer returns what the first call recorded and
     * moves nothing; the same reference for another customer is 409
     * DUPLICATE_REFERENCE.
     */
    @Transactional
    public TillResult tillCheckout(UUID tenantId, UUID shopId, String phoneNumber,
                                   BigDecimal cashAmount, BigDecimal pointsValue, String currency,
                                   TransactionType earnType, String reference) {
        boolean hasCash = cashAmount != null && cashAmount.signum() > 0;
        boolean hasPoints = pointsValue != null && pointsValue.signum() > 0;
        String mode = hasCash && hasPoints ? "mixed" : hasCash ? "cash" : "points";
        try {
            TillResult r = doTillCheckout(tenantId, shopId, phoneNumber, cashAmount, pointsValue, currency,
                    earnType, reference, hasCash, hasPoints);
            metrics.incShopCheckout("success", mode);
            return r;
        } catch (LoyaltyException e) {
            metrics.incShopCheckout("rejected", mode);
            metrics.incShopCheckoutRejected(e.getCode());
            throw e;
        }
    }

    private TillResult doTillCheckout(UUID tenantId, UUID shopId, String phoneNumber,
                                      BigDecimal cashAmount, BigDecimal pointsValue, String currency,
                                      TransactionType earnType, String reference,
                                      boolean hasCash, boolean hasPoints) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw LoyaltyException.badRequest("PHONE_REQUIRED", "Please provide your phone number.");
        }
        if (!hasCash && !hasPoints) {
            throw LoyaltyException.badRequest("AMOUNT_REQUIRED",
                    "at least one of cashAmount or pointsValue must be positive");
        }
        if (reference == null || reference.isBlank()) {
            throw LoyaltyException.badRequest("REFERENCE_REQUIRED", "A checkout reference is required.");
        }
        TransactionType type = earnType == null ? TransactionType.PURCHASE : earnType;
        if (type != TransactionType.PURCHASE && type != TransactionType.CARD_PAYMENT) {
            throw LoyaltyException.badRequest("BAD_TRANSACTION_TYPE",
                    "transactionType must be PURCHASE or CARD_PAYMENT.");
        }
        Shop shop = shops.findById(shopId)
                .filter(s -> tenantId.equals(s.getTenantId()))
                .orElseThrow(() -> LoyaltyException.notFound("shop"));
        if (shop.getStatus() != Shop.Status.ACTIVE) {
            throw LoyaltyException.badRequest("SHOP_INACTIVE", "This shop isn't accepting purchases right now.");
        }
        UUID merchantId = shop.getMerchantId();
        Merchant m = merchants.requireMerchant(tenantId, merchantId);
        if (m.getStatus() != Merchant.Status.ACTIVE) {
            throw LoyaltyException.badRequest("MERCHANT_INACTIVE",
                    "merchant is not active; no loyalty operations will run");
        }
        String ccy = currency == null || currency.isBlank() ? m.getCurrency() : currency;
        String burnRef = reference + REDEEM_SUFFIX;

        var replay = replay(tenantId, shopId, merchantId, phoneNumber, reference, burnRef, ccy);
        if (replay != null) {
            return replay;
        }

        UUID loyaltyUserId = null;
        BigDecimal balance = null;
        BigDecimal pointsRedeemed = BigDecimal.ZERO;
        BigDecimal valueCovered = BigDecimal.ZERO;
        UUID redemptionTxnId = null;
        if (hasPoints) {
            Dtos.RedemptionRequest burn = new Dtos.RedemptionRequest(
                    merchantId, null, null, "till-checkout", burnRef, pointsValue, ccy, phoneNumber);
            RedemptionService.RedemptionResult burned = redemptionService.redeemPoints(tenantId, merchantId, burn, true);
            redemptionTxnId = burned.transactionId();
            balance = burned.balance();
            LoyaltyTransaction row = transactions.findById(redemptionTxnId)
                    .orElseThrow(() -> new IllegalStateException("redemption row vanished: " + burned.transactionId()));
            // The burn stamps the caller's token shop, which a merchant or super
            // admin does not carry; the sale is this shop's either way.
            if (row.getShopId() == null) {
                row.setShopId(shopId);
            }
            loyaltyUserId = row.getUserId();
            pointsRedeemed = row.getPointsDelta().negate();
            valueCovered = row.getAmount();
            ccy = row.getCurrency();
        }

        BigDecimal pointsEarned = BigDecimal.ZERO;
        UUID purchaseTxnId = null;
        if (hasCash) {
            LoyaltyUser user = users.findOrCreatePending(tenantId, phoneNumber, merchantId);
            loyaltyUserId = user.getId();
            Dtos.TransactionRequest earn = new Dtos.TransactionRequest(
                    merchantId, null, phoneNumber, type, cashAmount, ccy, reference);
            Dtos.TransactionResponse earnResp = transactionService.post(tenantId, merchantId, earn, shopId,
                    com.innbucks.loyaltyservice.entity.EarnChannel.TYPED_PHONE);
            pointsEarned = earnResp.pointsDelta() == null ? BigDecimal.ZERO : earnResp.pointsDelta();
            purchaseTxnId = earnResp.id();
            // A 0-point earn reports balanceAfter 0, which is not the balance.
            if (pointsEarned.signum() > 0 && earnResp.balanceAfter() != null) {
                balance = earnResp.balanceAfter();
            }
        }
        if (balance == null) {
            balance = walletBalanceOrZero(users.normalizePhone(phoneNumber));
        }

        log.info("Till checkout shopId={} merchantId={} phone={} cash={} pointsRedeemed={} pointsEarned={} balance={}",
                shopId, merchantId, MsisdnMasking.mask(phoneNumber), cashAmount, pointsRedeemed, pointsEarned, balance);
        Result result = new Result(shopId, merchantId, tenantId, loyaltyUserId,
                hasCash ? cashAmount : BigDecimal.ZERO, pointsRedeemed, pointsEarned, balance,
                purchaseTxnId, redemptionTxnId);
        return new TillResult(result, valueCovered, ccy, false);
    }

    /**
     * The earlier checkout this reference names, if it is this customer's; null
     * when the reference is unused. Both legs of a checkout commit together, so
     * finding either leg means the whole checkout happened.
     */
    private TillResult replay(UUID tenantId, UUID shopId, UUID merchantId, String phoneNumber,
                              String reference, String burnRef, String ccy) {
        var earn = transactions.findFirstByMerchantIdAndReference(merchantId, reference);
        var burn = transactions.findFirstByMerchantIdAndReference(merchantId, burnRef);
        if (earn.isEmpty() && burn.isEmpty()) {
            return null;
        }
        LoyaltyUser u = users.findByPhone(tenantId, phoneNumber).orElse(null);
        boolean mine = u != null
                && earn.map(t -> t.getType() != TransactionType.REDEMPTION && u.getId().equals(t.getUserId()))
                        .orElse(true)
                && burn.map(t -> t.getType() == TransactionType.REDEMPTION && u.getId().equals(t.getUserId()))
                        .orElse(true);
        if (!mine) {
            throw LoyaltyException.conflict("DUPLICATE_REFERENCE",
                    "transaction with this merchant reference already exists");
        }
        BigDecimal balance = walletBalanceOrZero(u.getPhoneNumber());
        Result result = new Result(shopId, merchantId, tenantId, u.getId(),
                earn.map(LoyaltyTransaction::getAmount).orElse(BigDecimal.ZERO),
                burn.map(t -> t.getPointsDelta().negate()).orElse(BigDecimal.ZERO),
                earn.map(LoyaltyTransaction::getPointsDelta).orElse(BigDecimal.ZERO),
                balance,
                earn.map(LoyaltyTransaction::getId).orElse(null),
                burn.map(LoyaltyTransaction::getId).orElse(null));
        return new TillResult(result,
                burn.map(LoyaltyTransaction::getAmount).orElse(BigDecimal.ZERO),
                burn.map(LoyaltyTransaction::getCurrency).orElse(earn.map(LoyaltyTransaction::getCurrency).orElse(ccy)),
                true);
    }

    private BigDecimal walletBalanceOrZero(String phoneNumber) {
        return wallets.findByPhoneNumber(phoneNumber).stream()
                .filter(w -> w.getType() == com.innbucks.loyaltyservice.entity.Wallet.Type.MAIN)
                .map(w -> w.getBalance() == null ? BigDecimal.ZERO : w.getBalance())
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }
}
