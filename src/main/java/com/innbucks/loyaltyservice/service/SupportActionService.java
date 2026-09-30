package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.SupportDtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.SupportActivity;
import com.innbucks.loyaltyservice.entity.SupportNote;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyRefreshTokenRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.security.SupportAgent;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The things an agent may DO to a customer's loyalty record, as opposed to read.
 *
 * <p>Every action here is bound to the looked-up customer before it touches
 * anything: a membership, a transaction or a voucher belonging to a different
 * phone is a 404, the same as one that does not exist. And every action reuses
 * the service's existing logic rather than a support copy of it — the same
 * adjustment ceilings, the same reversal guards, the same unblock rule — called
 * through narrow methods that take the acting agent explicitly, so nothing here
 * depends on, or widens, the role lists the merchant surfaces authorize with.
 *
 * <p>Each action writes one {@code support_activity} row in its own transaction,
 * so the row exists iff the action happened. The row carries ids and amounts
 * only; the agent's REASON is stored where the action already keeps one (the
 * transaction reference, the ledger entry), or — for a sign-out or an unblock,
 * whose tables have nowhere to put it — as an internal note on the customer,
 * whose id the activity row names.
 */
@Service
@Slf4j
public class SupportActionService {

    /** {@code loyalty_refresh_tokens.revoked_reason} is VARCHAR(64). */
    static final int MAX_REVOKE_REASON = 64;

    private final SupportCustomerService customers;
    private final SupportNoteService notes;
    private final SupportActivityService activity;
    private final TransactionService transactionService;
    private final UserService userService;
    private final LoyaltySessionService sessions;
    private final LoyaltyRefreshTokenRepository refreshTokens;
    private final LoyaltyTransactionRepository transactions;
    private final MerchantRepository merchants;
    private final TenantRepository tenants;

    public SupportActionService(SupportCustomerService customers, SupportNoteService notes,
                                SupportActivityService activity, TransactionService transactionService,
                                UserService userService, LoyaltySessionService sessions,
                                LoyaltyRefreshTokenRepository refreshTokens,
                                LoyaltyTransactionRepository transactions, MerchantRepository merchants,
                                TenantRepository tenants) {
        this.customers = customers;
        this.notes = notes;
        this.activity = activity;
        this.transactionService = transactionService;
        this.userService = userService;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.transactions = transactions;
        this.merchants = merchants;
        this.tenants = tenants;
    }

    /**
     * Signs the customer out of every loyalty session: revokes every refresh
     * chain of the phone, so no device can renew. An access token already
     * issued keeps working until it expires (its TTL is its only end — it
     * carries no user id for the fleet denylist to reach); what stops now is
     * renewal.
     */
    @Transactional
    public SupportDtos.SignOutResponse signOut(SupportAgent agent, UUID lookupId, String rawReason) {
        SupportCustomerService.Customer customer = customers.resolve(agent, lookupId);
        String reason = cleanReason(rawReason);
        long chains = refreshTokens.countActiveChains(customer.phone(), Instant.now());
        int rows = sessions.revokeAllForPhone(customer.phone(), revokeReason(agent));
        SupportNote note = notes.recordActionReason(agent, customer.phone(),
                "Signed the customer out of every loyalty session (" + chains + " active). Reason: " + reason);
        activity.record(agent, SupportActivity.Action.SESSIONS_REVOKED, SupportActivity.SUBJECT_PHONE,
                customer.phone(), SupportActivityService.detail("lookupId", lookupId,
                        "chainsRevoked", chains, "tokensRevoked", rows, "noteId", note.getId()));
        return new SupportDtos.SignOutResponse(chains, rows, note.getId());
    }

    /**
     * Credits or debits points on one of the customer's memberships through
     * {@link TransactionService#adjustAs}: the SAME ceilings as a merchant
     * adjustment (per adjustment, and per operator per 24h keyed on
     * {@code posted_by} = the agent), the SAME SUPER_ADMIN exemption and the
     * SAME customer SMS. A supervisor is not exempt.
     */
    @Transactional
    public Dtos.TransactionResponse adjust(SupportAgent agent, UUID lookupId, SupportDtos.AdjustRequest req) {
        UUID operator = agent.requireUserUuid();
        SupportCustomerService.Customer customer = customers.resolve(agent, lookupId);
        LoyaltyUser membership = customers.requireMembership(customer, req.userId());
        Merchant merchant = merchants.findById(req.merchantId())
                .filter(m -> m.getTenantId().equals(membership.getTenantId()))
                .orElseThrow(() -> new LoyaltyException(HttpStatus.NOT_FOUND, "merchant_not_found",
                        "No such merchant in this membership's loyalty programme."));
        // Whole points: @Digits(fraction = 0) already refused a fraction, so this
        // rescale is exact (5.0 -> 5) and never rounds.
        BigDecimal points = req.points().setScale(0, java.math.RoundingMode.UNNECESSARY);
        if (points.signum() == 0) {
            throw LoyaltyException.badRequest("invalid_points", "points must be a whole number other than 0.");
        }
        String reason = cleanReason(req.reason());
        Dtos.TransactionResponse result = transactionService.adjustAs(membership.getTenantId(), membership.getId(),
                merchant.getId(), points, reason, operator);
        activity.record(agent, SupportActivity.Action.POINTS_ADJUSTED, SupportActivity.SUBJECT_PHONE,
                customer.phone(), SupportActivityService.detail("lookupId", lookupId,
                        "transactionId", result.id(), "userId", membership.getId(),
                        "tenantId", membership.getTenantId(), "merchantId", merchant.getId(),
                        "points", points));
        return result;
    }

    /**
     * Reverses one of the customer's transactions through
     * {@link TransactionService#reverseAs}: the same row lock, the same
     * ALREADY_REVERSED guard, the same ledger-derived compensation.
     */
    @Transactional
    public Dtos.TransactionResponse reverse(SupportAgent agent, UUID lookupId, UUID transactionId,
                                            String rawReason) {
        UUID operator = agent.requireUserUuid();
        SupportCustomerService.Customer customer = customers.resolve(agent, lookupId);
        LoyaltyTransaction txn = transactions.findById(transactionId)
                .filter(t -> customer.membershipIds().contains(t.getUserId()))
                .orElseThrow(() -> new LoyaltyException(HttpStatus.NOT_FOUND, "transaction_not_found",
                        "This customer has no transaction with that id."));
        String reason = cleanReason(rawReason);
        Dtos.TransactionResponse reversal = transactionService.reverseAs(txn.getTenantId(), txn.getId(),
                reason, operator);
        activity.record(agent, SupportActivity.Action.TRANSACTION_REVERSED, SupportActivity.SUBJECT_PHONE,
                customer.phone(), SupportActivityService.detail("lookupId", lookupId,
                        "transactionId", txn.getId(), "reversalId", reversal.id(),
                        "tenantId", txn.getTenantId(), "pointsDelta", reversal.pointsDelta()));
        return reversal;
    }

    /**
     * Lifts a fraud hold on one of the customer's memberships through
     * {@link UserService#unblock} — BLOCKED only; anything else keeps its
     * {@code 409 USER_NOT_BLOCKED}.
     */
    @Transactional
    public SupportDtos.UnblockResponse unblock(SupportAgent agent, UUID lookupId, UUID userId, String rawReason) {
        SupportCustomerService.Customer customer = customers.resolve(agent, lookupId);
        LoyaltyUser membership = customers.requireMembership(customer, userId);
        String reason = cleanReason(rawReason);
        LoyaltyUser unblocked = userService.unblock(membership.getTenantId(), membership.getId());
        String tenantName = tenants.findById(membership.getTenantId()).map(Tenant::getName).orElse(null);
        SupportNote note = notes.recordActionReason(agent, customer.phone(),
                "Lifted the fraud hold on membership " + membership.getId()
                        + (tenantName == null ? "" : " (" + tenantName + ")") + ". Reason: " + reason);
        activity.record(agent, SupportActivity.Action.MEMBERSHIP_UNBLOCKED, SupportActivity.SUBJECT_PHONE,
                customer.phone(), SupportActivityService.detail("lookupId", lookupId,
                        "userId", membership.getId(), "tenantId", membership.getTenantId(),
                        "noteId", note.getId()));
        return new SupportDtos.UnblockResponse(SupportCustomerService.toMembership(unblocked, tenantName),
                note.getId());
    }

    /** What the refresh-token rows record as the revocation reason: who, not why. */
    static String revokeReason(SupportAgent agent) {
        String reason = "support:" + agent.uuid();
        return reason.length() > MAX_REVOKE_REASON ? reason.substring(0, MAX_REVOKE_REASON) : reason;
    }

    /** HTML stripped (stored-XSS hardening), trimmed, and not empty. The DTO bounds the length. */
    private static String cleanReason(String raw) {
        String reason = raw == null ? "" : HtmlSanitizer.stripAll(raw).strip();
        if (reason.isEmpty()) {
            throw LoyaltyException.badRequest("invalid_reason", "Give a reason. It is empty once formatting is removed.");
        }
        return reason;
    }
}
