package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TransactionService {

    private final LoyaltyTransactionRepository transactions;
    private final UserService users;
    private final MerchantService merchants;
    private final WalletService walletService;
    private final RulesEngine rulesEngine;
    private final com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics;
    private final com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier;

    public TransactionService(LoyaltyTransactionRepository transactions,
                              UserService users,
                              MerchantService merchants,
                              WalletService walletService,
                              RulesEngine rulesEngine,
                              com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics,
                              com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier) {
        this.transactions = transactions;
        this.users = users;
        this.merchants = merchants;
        this.walletService = walletService;
        this.rulesEngine = rulesEngine;
        this.metrics = metrics;
        this.memberNotifier = memberNotifier;
    }

    public Dtos.TransactionResponse post(UUID tenantId, UUID merchantId, Dtos.TransactionRequest req) {
        // JWT-gated callers (SHOP_USER / SHOP_ADMIN): attribute the transaction to
        // the shop on the caller's token. Server-side callers that resolved the
        // shop from a trusted path param use the overload below.
        Dtos.TransactionResponse resp = post(tenantId, merchantId, req,
                com.innbucks.loyaltyservice.security.CallerDetails.currentShopId());
        // Earn alert for the registered customer. Fires only on this (3-arg)
        // entry — the public /loyalty/transactions endpoint plus the QR and
        // ticketing accrual flows — NOT the 4-arg overload the guest /
        // shop-checkout path calls (that path already notifies via
        // GuestCheckoutNotifier, so routing earn here avoids double-texting).
        if (resp.pointsDelta() != null && resp.pointsDelta().signum() > 0) {
            String phone = (req.assigneePhone() != null && !req.assigneePhone().isBlank())
                    ? req.assigneePhone()
                    : users.require(tenantId, req.userId()).getPhoneNumber();
            memberNotifier.notifyPointsEarned(phone, resp.pointsDelta(), resp.balanceAfter());
        }
        return resp;
    }

    /**
     * Post a transaction with an explicitly-resolved {@code shopId} rather than
     * reading it from the caller's JWT. Used by server-side flows (guest / shop
     * checkout) that resolve the shop themselves and carry no JWT — without this
     * their transactions land with a null shop and never show up in the per-shop
     * points report.
     */
    public Dtos.TransactionResponse post(UUID tenantId, UUID merchantId, Dtos.TransactionRequest req, UUID shopId) {
        Merchant m = merchants.requireMerchant(tenantId, merchantId);
        if (m.getStatus() != Merchant.Status.ACTIVE) {
            throw LoyaltyException.badRequest("MERCHANT_INACTIVE", "This merchant isn't accepting points right now.");
        }
        // Recipient is either an existing LoyaltyUser (userId) or a phone we
        // lazily enrol as PENDING. Exactly one input is required.
        boolean hasUserId = req.userId() != null;
        boolean hasPhone = req.assigneePhone() != null && !req.assigneePhone().isBlank();
        if (hasUserId == hasPhone) {
            throw LoyaltyException.badRequest("RECIPIENT_REQUIRED",
                    "supply exactly one of userId or assigneePhone");
        }
        LoyaltyUser u = hasUserId
                ? users.require(tenantId, req.userId())
                : users.findOrCreatePending(tenantId, req.assigneePhone(), merchantId);
        // Accrual is allowed for PENDING (the whole point of the feature);
        // BLOCKED still rejects so fraud holds stick.
        if (u.getStatus() == LoyaltyUser.Status.BLOCKED) {
            throw LoyaltyException.forbidden("USER_BLOCKED", "Your account is currently suspended. Please contact support.");
        }

        if (req.reference() != null) {
            transactions.findFirstByMerchantIdAndReference(merchantId, req.reference())
                    .ifPresent(existing -> {
                        throw LoyaltyException.conflict("DUPLICATE_REFERENCE",
                                "transaction with this merchant reference already exists");
                    });
        }

        var eval = rulesEngine.evaluate(tenantId, m.getId(), req.type(), req.amount());

        LoyaltyTransaction t = new LoyaltyTransaction();
        t.setTenantId(tenantId);
        t.setMerchantId(m.getId());
        // shopId attributes the transaction to an outlet: from the caller's JWT
        // (SHOP_USER / SHOP_ADMIN) via the public endpoint, or resolved
        // server-side for guest / shop checkout. Outlet-scoped reporting (the
        // per-shop points report and the /loyalty/transactions/my-shop feed)
        // keys off this column, so a null here hides the transaction from them.
        t.setShopId(shopId);
        t.setUserId(u.getId());
        t.setType(req.type());
        t.setAmount(req.amount());
        t.setCurrency(req.currency() == null ? m.getCurrency() : req.currency());
        t.setPointsDelta(eval.points());
        t.setRuleId(eval.ruleId());
        t.setCampaignId(eval.campaignId());
        t.setReference(req.reference());
        // saveAndFlush so a unique-constraint violation on (merchant_id, reference)
        // surfaces here as DataIntegrityViolationException rather than at txn
        // commit (where it would bubble up as a 500). The Java pre-check above
        // narrows the window; the DB constraint closes it.
        try {
            transactions.saveAndFlush(t);
        } catch (DataIntegrityViolationException dup) {
            throw LoyaltyException.conflict("DUPLICATE_REFERENCE",
                    "transaction with this merchant reference already exists");
        }

        BigDecimal balance = BigDecimal.ZERO;
        if (eval.points().signum() > 0) {
            // All points land in the customer's single global MAIN wallet (no
            // pocket routing), so every earned point is fungible and spendable
            // on anything in the app.
            Wallet wallet = walletService.mainWallet(u.getPhoneNumber());
            balance = walletService.apply(wallet.getId(), eval.points(), t.getId(),
                    "earn:" + req.type().name(), tenantId);
        }

        metrics.incTransactionPosted(t.getType().name());
        metrics.addPointsEarned(eval.points());
        return toResponse(t, balance);
    }

    public Dtos.TransactionResponse reverse(UUID tenantId, UUID txnId, String reason) {
        // Sanitize the operator-supplied reason — it is persisted in the wallet
        // ledger entry as "reverse:<reason>" (stored-XSS hardening). Null-safe.
        reason = HtmlSanitizer.stripAll(reason);
        // PESSIMISTIC_WRITE lock on the original so two concurrent reversals
        // serialize: the loser blocks here, then re-reads status=REVERSED below
        // and is rejected — instead of both inserting a compensating credit and
        // crediting the wallet twice (points created out of nothing).
        LoyaltyTransaction orig = transactions.lockById(txnId)
                .orElseThrow(() -> LoyaltyException.notFound("transaction"));
        if (!orig.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        }
        if (orig.getStatus() == LoyaltyTransaction.Status.REVERSED) {
            throw LoyaltyException.conflict("ALREADY_REVERSED", "This transaction has already been reversed.");
        }
        orig.setStatus(LoyaltyTransaction.Status.REVERSED);

        LoyaltyTransaction rev = new LoyaltyTransaction();
        rev.setTenantId(tenantId);
        rev.setMerchantId(orig.getMerchantId());
        // Reversal inherits the original's shop attribution so it shows up
        // in the same outlet's "my-shop" feed.
        rev.setShopId(orig.getShopId());
        rev.setUserId(orig.getUserId());
        rev.setType(TransactionType.ADJUSTMENT);
        rev.setAmount(orig.getAmount());
        rev.setCurrency(orig.getCurrency());
        rev.setPointsDelta(orig.getPointsDelta().negate());
        rev.setReversesId(orig.getId());
        rev.setReference(orig.getReference() == null ? null : "REV-" + orig.getReference());
        // saveAndFlush so the uq_txn_reverses_id unique index (V20) surfaces a
        // second reversal as a clean ALREADY_REVERSED here — and crucially
        // BEFORE we credit the wallet below — rather than as a 500 at commit.
        // Belt-and-suspenders with the row lock above.
        try {
            transactions.saveAndFlush(rev);
        } catch (DataIntegrityViolationException dup) {
            throw LoyaltyException.conflict("ALREADY_REVERSED", "This transaction has already been reversed.");
        }

        BigDecimal balance = BigDecimal.ZERO;
        if (rev.getPointsDelta().signum() != 0) {
            // The compensating credit/debit lands on the customer's GLOBAL wallet
            // (resolved by the original owner's phone).
            String phone = users.require(tenantId, orig.getUserId()).getPhoneNumber();
            Wallet w = walletService.mainWallet(phone);
            balance = walletService.apply(w.getId(), rev.getPointsDelta(), rev.getId(),
                    "reverse:" + (reason == null ? "n/a" : reason), tenantId);
        }

        metrics.incTransactionPosted("REVERSAL");
        return toResponse(rev, balance);
    }

    public Dtos.TransactionResponse adjust(UUID tenantId, UUID userId, UUID merchantId,
                                           BigDecimal points, String reason) {
        // Sanitize the operator-supplied reason once — it is persisted both as the
        // transaction reference AND in the wallet ledger entry (stored-XSS hardening).
        reason = HtmlSanitizer.stripAll(reason);
        Merchant m = merchants.requireMerchant(tenantId, merchantId);
        LoyaltyUser u = users.require(tenantId, userId);
        LoyaltyTransaction t = new LoyaltyTransaction();
        t.setTenantId(tenantId);
        t.setMerchantId(merchantId);
        t.setUserId(u.getId());
        t.setType(TransactionType.ADJUSTMENT);
        t.setPointsDelta(points);
        // Adjustments inherit the merchant's currency (previously left at the
        // entity "USD" default — the audit's mislabelled-ledger finding).
        t.setCurrency(m.getCurrency());
        t.setReference(reason);
        transactions.save(t);

        Wallet w = walletService.mainWallet(u.getPhoneNumber());
        BigDecimal balance = walletService.apply(w.getId(), points, t.getId(),
                "adjust:" + (reason == null ? "n/a" : reason), tenantId);
        metrics.incTransactionPosted("ADJUSTMENT");
        // Unexpected balance changes are worth surfacing (credit or debit).
        memberNotifier.notifyPointsAdjusted(u.getPhoneNumber(), points, balance);
        return toResponse(t, balance);
    }

    @Transactional(readOnly = true)
    public List<Dtos.TransactionResponse> recentForUser(UUID userId) {
        return transactions.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(t -> toResponse(t, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<Dtos.TransactionResponse> recentForUser(UUID userId, Pageable pageable) {
        return transactions.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(t -> toResponse(t, null));
    }

    @Transactional(readOnly = true)
    public Page<Dtos.TransactionResponse> recentForShop(UUID tenantId, UUID shopId, Pageable pageable) {
        return transactions
                .findByTenantIdAndShopIdOrderByCreatedAtDesc(tenantId, shopId, pageable)
                .map(t -> toResponse(t, null));
    }

    /**
     * {@code balanceAfter} is only populated on the write paths
     * ({@link #post}, {@link #reverse}, {@link #adjust}) where the call site
     * knows the wallet balance it just computed. The read paths
     * ({@link #recentForUser}, {@link #recentForShop}) pass {@code null} to
     * avoid a per-row wallet lookup — the SuperApp activity feed and the
     * shop-staff feed don't need the running balance per row, they call the
     * dedicated balance endpoint when they want it.
     */
    private static Dtos.TransactionResponse toResponse(LoyaltyTransaction t, BigDecimal balance) {
        return new Dtos.TransactionResponse(t.getId(), t.getType(), t.getAmount(),
                t.getPointsDelta(), balance, t.getRuleId(), t.getCampaignId(),
                t.getShopId(), t.getReference(), t.getCreatedAt());
    }
}
