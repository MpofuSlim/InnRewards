package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional
public class RedemptionService {

    private final UserService users;
    private final MerchantService merchants;
    private final WalletService walletService;
    private final LoyaltyTransactionRepository transactions;
    private final LoyaltyMetrics metrics;
    private final RedemptionRateService rateService;
    private final com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier;

    public RedemptionService(UserService users, MerchantService merchants,
                             WalletService walletService,
                             LoyaltyTransactionRepository transactions,
                             LoyaltyMetrics metrics,
                             RedemptionRateService rateService,
                             com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier) {
        this.users = users;
        this.merchants = merchants;
        this.walletService = walletService;
        this.transactions = transactions;
        this.metrics = metrics;
        this.rateService = rateService;
        this.memberNotifier = memberNotifier;
    }

    /**
     * Outcome of a redemption: both the new wallet balance and the ledger
     * transaction id. The id lets shop-checkout / receipts surface the
     * specific REDEMPTION row a customer can later quote for support.
     */
    public record RedemptionResult(UUID transactionId, BigDecimal balance) {}

    /**
     * Redeem points for in-platform credit (e.g. discount). Returns the new
     * balance plus the ledger transaction id for receipts/reconciliation.
     */
    public RedemptionResult redeemPoints(UUID tenantId, UUID merchantId, Dtos.RedemptionRequest req) {
        // S2S / internal callers (shop-checkout, ticketing) carry no JWT and are
        // trusted via the internal-token boundary, so they don't enforce caller
        // ownership. The public /loyalty/redeem endpoint passes true (below).
        return redeemPoints(tenantId, merchantId, req, false);
    }

    public RedemptionResult redeemPoints(UUID tenantId, UUID merchantId, Dtos.RedemptionRequest req,
                                         boolean enforceCallerOwnership) {
        if ((req.points() == null || req.points().signum() <= 0)
                && (req.amount() == null || req.amount().signum() <= 0)) {
            throw LoyaltyException.badRequest("BAD_AMOUNT",
                    "Provide points, or a currency amount, greater than zero.");
        }
        var u = users.require(tenantId, req.userId());
        // A JWT caller (CUSTOMER) may only redeem their OWN balance; admins may
        // act on behalf. Without this a logged-in customer could burn — or, via
        // the idempotent-replay branch below, read the balance of — ANY user by
        // passing that user's id.
        if (enforceCallerOwnership) {
            users.requireCallerOwnsOrIsAdmin(u);
        }
        // PENDING (not yet registered) users may accrue but not spend.
        users.requireSpendable(u);
        var m = merchants.requireMerchant(tenantId, merchantId);

        // Idempotency: when the caller supplies a stable reference (e.g. the
        // booking id), a repeat redeem must NOT debit the wallet a second time.
        // A retry (network blip, double-tap) replays the original redemption.
        // The uq_txn_merchant_reference partial unique index (V16, which covers
        // REDEMPTION rows) is the race backstop behind this pre-check.
        String reference = req.reference();
        if (reference != null) {
            var existing = transactions.findFirstByMerchantIdAndReference(m.getId(), reference);
            if (existing.isPresent()) {
                LoyaltyTransaction prior = existing.get();
                if (prior.getType() == TransactionType.REDEMPTION) {
                    // Same logical redemption already happened — replay it, no second debit.
                    return new RedemptionResult(prior.getId(),
                            walletService.mainWallet(u.getPhoneNumber()).getBalance());
                }
                // Reference is already owned by a different transaction type
                // (e.g. a PURCHASE) — refuse rather than silently mis-attribute.
                throw LoyaltyException.conflict("DUPLICATE_REFERENCE",
                        "This reference is already used by a non-redemption transaction.");
            }
        }

        // The redemption formula (business-model point 4): the PLATFORM decides
        // what a point is worth, not the caller's till. When the request carries a
        // currency `amount`, the server computes the whole-points debit from the
        // platform rate; a legacy points-only request is still honoured, but either
        // way the dollar value the platform is liable for is computed here and
        // stamped on the ledger row (below). Supplying both a points and an amount
        // that disagree at the current rate is refused rather than trusting the
        // caller's number.
        String currency = m.getCurrency();
        BigDecimal pointsToDebit;
        if (req.amount() != null) {
            pointsToDebit = rateService.pointsFor(req.amount(), currency);
            if (req.points() != null && req.points().compareTo(pointsToDebit) != 0) {
                throw LoyaltyException.badRequest("RATE_MISMATCH",
                        "points (" + req.points().toPlainString() + ") does not match the "
                                + pointsToDebit.toPlainString() + " points that " + req.amount().toPlainString()
                                + " " + currency + " converts to at the current redemption rate. Send only "
                                + "one of points/amount, or make them agree.");
            }
        } else {
            pointsToDebit = req.points();
        }
        // Dollar value the platform honours for this burn — the liability figure,
        // recorded in money terms on every redemption (feeds the outstanding-points
        // valuation reporting).
        BigDecimal value = rateService.valueOf(pointsToDebit, currency);

        LoyaltyTransaction t = new LoyaltyTransaction();
        // Attribution (V32): the caller who keyed the redemption (cashier or
        // customer). Channel stays null — a redemption isn't an earn.
        t.setPostedBy(com.innbucks.loyaltyservice.security.CallerDetails.currentUserId());
        t.setTenantId(tenantId);
        t.setMerchantId(m.getId());
        t.setUserId(u.getId());
        t.setType(TransactionType.REDEMPTION);
        t.setPointsDelta(pointsToDebit.negate());
        // Money value + currency of the points burned, at the platform rate.
        t.setAmount(value);
        t.setCurrency(currency);
        // Store the idempotency reference when provided; otherwise fall back to
        // the free-text reason (unchanged behaviour for callers without a key).
        // Only the free-text reason is HTML-sanitized — the idempotency key is a
        // stable caller reference and must round-trip byte-for-byte.
        t.setReference(reference != null ? reference : HtmlSanitizer.stripAll(req.reason()));

        if (reference != null) {
            // saveAndFlush BEFORE the wallet debit so a concurrent duplicate
            // trips the unique index here (→ 409) rather than after points moved.
            try {
                transactions.saveAndFlush(t);
            } catch (DataIntegrityViolationException dup) {
                throw LoyaltyException.conflict("DUPLICATE_REFERENCE",
                        "A redemption with this reference is already being processed.");
            }
        } else {
            transactions.save(t);
        }

        Wallet w = walletService.mainWallet(u.getPhoneNumber());
        BigDecimal balance = walletService.apply(w.getId(), pointsToDebit.negate(), t.getId(),
                "redeem:" + (t.getReference() == null ? "n/a" : t.getReference()), tenantId);
        metrics.addPointsRedeemed(pointsToDebit);
        // Spend confirmation. The idempotent-replay branch above returns before
        // here, so a retried redemption never fires a second alert.
        memberNotifier.notifyPointsRedeemed(u.getPhoneNumber(), pointsToDebit, balance);
        return new RedemptionResult(t.getId(), balance);
    }
}
