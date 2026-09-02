package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.exception.RedemptionRaceException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    /** Self reference through the Spring proxy, so the retry in
     *  {@link #redeemPointsIdempotent} re-enters {@link #redeemPoints} across the
     *  proxy and gets a fresh transaction each attempt. */
    private final ObjectProvider<RedemptionService> self;

    public RedemptionService(UserService users, MerchantService merchants,
                             WalletService walletService,
                             LoyaltyTransactionRepository transactions,
                             LoyaltyMetrics metrics,
                             RedemptionRateService rateService,
                             com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier,
                             ObjectProvider<RedemptionService> self) {
        this.users = users;
        this.merchants = merchants;
        this.walletService = walletService;
        this.transactions = transactions;
        this.metrics = metrics;
        this.rateService = rateService;
        this.memberNotifier = memberNotifier;
        this.self = self;
    }

    /**
     * Idempotent, retry-safe entry point for the PUBLIC redeem endpoints
     * ({@code POST /loyalty/redeem} and its public-test twin). Wraps
     * {@link #redeemPoints} so a genuine concurrent double-tap — the
     * {@link RedemptionRaceException} flush-race — returns the clean 200 replay
     * instead of a 409: on the race the OTHER request already committed the
     * single debit, so re-running {@code redeemPoints} re-enters the idempotency
     * pre-check, finds that committed REDEMPTION row, and replays it.
     *
     * <p>Runs {@code NOT_SUPPORTED} (non-transactionally) so each
     * {@code self.redeemPoints(...)} call — through the Spring proxy — gets its
     * OWN transaction: the first attempt's tx rolls back cleanly on the race
     * before the second attempt's fresh tx reads the winner. (A same-transaction
     * re-read would be unsafe — a Postgres unique-violation aborts the whole
     * transaction — which is why the retry happens ABOVE the transactional
     * method, not inside its catch.)
     *
     * <p>Exactly ONE retry: the winner is guaranteed committed by the time the
     * loser observes the duplicate-key violation (Postgres blocks the conflicting
     * insert until the other transaction resolves), and ledger rows are
     * append-only, so the retry's pre-check WILL find the row and return before
     * any second insert. A second, unexpected race is allowed to propagate as the
     * original 409.
     *
     * <p>Only {@link RedemptionRaceException} is retried. The cross-type pre-check
     * conflict (a plain {@link LoyaltyException}, same 409 code, thrown BEFORE any
     * insert when the reference belongs to a non-redemption transaction) is a
     * genuine error and propagates on the first attempt — retrying it would loop
     * and could mask a real collision as success.
     *
     * <p>In-process callers (shop-checkout, ticketing) deliberately do NOT use
     * this wrapper: they join {@code redeemPoints}' transaction, so a race there
     * poisons their whole transaction and cannot be retried at this level. They
     * keep calling {@link #redeemPoints} directly and still see the identical 409
     * (a {@link RedemptionRaceException} IS a {@link LoyaltyException}).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RedemptionResult redeemPointsIdempotent(UUID tenantId, UUID merchantId,
                                                   Dtos.RedemptionRequest req,
                                                   boolean enforceCallerOwnership) {
        try {
            return self.getObject().redeemPoints(tenantId, merchantId, req, enforceCallerOwnership);
        } catch (RedemptionRaceException race) {
            // Lost the (merchant, reference) insert race — the winner already
            // committed the single debit. Retry ONCE: the pre-check now finds that
            // committed REDEMPTION row and replays it as a clean 200, no double
            // debit. A still-racing second attempt propagates as the 409.
            return self.getObject().redeemPoints(tenantId, merchantId, req, enforceCallerOwnership);
        }
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
                // Lost the unique-index race: the concurrent winner committed the
                // single debit; this flow never reached the wallet. Typed so the
                // idempotent wrapper can retry it into a clean 200 replay, while a
                // caller that lets it propagate still gets the same 409 it always did.
                throw new RedemptionRaceException();
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
