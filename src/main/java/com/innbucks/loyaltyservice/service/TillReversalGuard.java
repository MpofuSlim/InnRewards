package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Who may reverse a transaction from a till, decided before
 * {@link TransactionService#reverse} runs.
 *
 * <p>A reversal is a supervisor's call (SHOP_ADMIN and above), and a person who
 * is only a cashier is still refused. The one exception is a <b>POS API key</b>
 * (ticketing V51): a merchant's POS backend refunds its own sales through the
 * API, with no person signed in. A key may reverse only what that same key
 * posted (its earns and points burns), and only within
 * {@code loyalty.pos.reversal-window-days} (default 30). Anything older, or
 * posted by someone else, goes to a supervisor.
 *
 * <p>The checks read fields that never change after a row is written (who posted
 * it, when, its type), so reading them before {@code reverse} takes its row lock
 * is safe. A missing, other-tenant or other-merchant transaction is left to
 * {@code reverse}, which answers it the way it always has (404 / CROSS_TENANT /
 * WRONG_MERCHANT), so the key learns nothing new about rows that are not its own.
 */
@Component
public class TillReversalGuard {

    /** What a till posts. A reversal row (ADJUSTMENT) is not one: undoing a refund is a supervisor's call. */
    private static final Set<TransactionType> TILL_POSTED =
            Set.of(TransactionType.PURCHASE, TransactionType.CARD_PAYMENT, TransactionType.REDEMPTION);

    private final LoyaltyTransactionRepository transactions;
    private final LoyaltyMetrics metrics;
    private final Duration window;

    public TillReversalGuard(LoyaltyTransactionRepository transactions, LoyaltyMetrics metrics,
                             @Value("${loyalty.pos.reversal-window-days:30}") int windowDays) {
        this.transactions = transactions;
        this.metrics = metrics;
        this.window = Duration.ofDays(windowDays > 0 ? windowDays : 30);
    }

    /**
     * Refuses a caller who may not reverse anything: a cashier signed in as a
     * person. Runs before the tenant is resolved, so that caller is told why
     * (403) whatever headers it sent.
     */
    public void requireMayReverse() {
        if (!CallerDetails.isPosApiKey()
                && !CallerDetails.hasAnyRole("ROLE_SHOP_ADMIN", "ROLE_MERCHANT_ADMIN", "ROLE_SUPER_ADMIN")) {
            throw LoyaltyException.forbidden("REVERSAL_NOT_PERMITTED",
                    "Only a supervisor can reverse a transaction.");
        }
    }

    /** For a POS API key: the transaction must be a sale this key posted, inside the window. */
    public void check(UUID tenantId, UUID transactionId) {
        if (!CallerDetails.isPosApiKey()) {
            return;
        }
        LoyaltyTransaction orig = transactions.findById(transactionId).orElse(null);
        if (orig == null || !tenantId.equals(orig.getTenantId())) {
            return;
        }
        UUID callerMerchant = CallerDetails.currentMerchantId();
        if (orig.getMerchantId() != null && callerMerchant != null && !orig.getMerchantId().equals(callerMerchant)) {
            return;
        }
        UUID key = CallerDetails.currentUserId();
        if (!key.equals(orig.getPostedBy()) || !TILL_POSTED.contains(orig.getType())
                || orig.getReversesId() != null) {
            metrics.incPosKeyReversal(LoyaltyMetrics.POS_REVERSAL_NOT_OWN);
            throw LoyaltyException.forbidden("REVERSAL_NOT_PERMITTED",
                    "This API key can only reverse sales it posted. Ask a supervisor to reverse this one.");
        }
        if (orig.getCreatedAt().isBefore(Instant.now().minus(window))) {
            metrics.incPosKeyReversal(LoyaltyMetrics.POS_REVERSAL_TOO_OLD);
            throw LoyaltyException.forbidden("REVERSAL_WINDOW_PASSED",
                    "This sale is more than " + window.toDays() + " days old. Ask a supervisor to reverse it.");
        }
        metrics.incPosKeyReversal(LoyaltyMetrics.POS_REVERSAL_ALLOWED);
    }
}
