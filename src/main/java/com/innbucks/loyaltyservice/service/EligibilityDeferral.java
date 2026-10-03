package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.exception.EligibilityCheckDeferred;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

/**
 * Keeps the on-demand eligibility check (V44, {@link OnDemandEligibilityCheck})
 * OUT of the spend transaction.
 *
 * <h2>Why</h2>
 * The check is an HTTP call to the InnBucks directory plus a Redis claim, and it
 * runs at the spend gate ({@link UserService#spendabilityOf}) — which sits inside
 * the caller's transaction on every spend path: points redeem, P2P transfer, the
 * transfer-QR consume, shop checkout, the ticketing burn, and voucher redeem,
 * where the gate also runs under the voucher's {@code PESSIMISTIC_WRITE} row
 * lock. Run there, a slow directory held a pooled connection, and the voucher
 * row, for the whole round trip.
 *
 * <h2>How: defer, then replay once</h2>
 * {@link #run} opens a deferral scope (a thread-local) and runs the spend. When
 * the gate meets an unregistered phone that the check could answer for, it
 * throws {@link EligibilityCheckDeferred} instead of asking. That rolls the
 * spend back — safe, because nothing on any spend path commits or writes on
 * its own before the gate (refusal evidence, the redeem audit event and every
 * {@code REQUIRES_NEW} write sit on branches that THROW before the gate is
 * reached). With no transaction open, this class then:
 * <ol>
 *   <li>asks the directory ({@link OnDemandEligibilityCheck#confirmsCustomer} —
 *       same throttle, same metrics, same "never throws");</li>
 *   <li>on a confirmed customer, registers the phone through the
 *       {@link UserService} PROXY, so the registration commits in its own
 *       transaction;</li>
 *   <li>replays the spend exactly ONCE, with the directory switched off for
 *       the replay. The gate then reads the FACT: a registered phone heals and
 *       spends; a phone still unregistered — the directory said no, was down,
 *       was throttled, or the registration is REVOKED, which an
 *       eligibility-only proof deliberately leaves revoked — gets the ordinary
 *       {@code USER_PENDING} refusal. Never a loop, never a second directory
 *       call for one request.</li>
 * </ol>
 *
 * <h2>Behaviour change, accepted</h2>
 * A registration earned on this path now COMMITS even when the replayed spend
 * then fails (INSUFFICIENT_FUNDS, say). Under the owner's rule every InnBucks
 * customer is eligible, and the backlog sweeper or the registration endpoint
 * would register the same phone anyway; only the timing moved.
 *
 * <h2>Without a scope, nothing changes</h2>
 * A caller that does not go through here — and a call made while a transaction
 * is already open, where rolling back would poison the caller's transaction —
 * runs exactly as before: the gate asks inline. So a deferral can never escape
 * as a 500. Every controller that reaches the gate wraps its call;
 * {@code EligibilityDeferralWiringTest} fails the build on one that does not.
 */
@Component
@Slf4j
public class EligibilityDeferral {

    /** What the spend gate should do with an unregistered phone on this thread. */
    public enum Mode {
        /** No scope: ask the directory inline, inside the caller's transaction (legacy). */
        NONE,
        /** First attempt inside a scope: throw {@link EligibilityCheckDeferred} instead of asking. */
        DEFER,
        /** The single replay: the directory has already been asked for this request — do not ask again. */
        REPLAY
    }

    private static final ThreadLocal<Mode> MODE = new ThreadLocal<>();

    private final OnDemandEligibilityCheck eligibility;
    private final UserService users;

    public EligibilityDeferral(OnDemandEligibilityCheck eligibility, UserService users) {
        this.eligibility = eligibility;
        this.users = users;
    }

    /** The mode the spend gate on this thread is running under. */
    public static Mode currentMode() {
        Mode m = MODE.get();
        return m == null ? Mode.NONE : m;
    }

    /**
     * Run a spend with the eligibility check deferred out of its transaction.
     *
     * @param spend the whole spend, starting its own transaction(s) — it must be
     *              safe to run twice when its first run threw
     *              {@link EligibilityCheckDeferred} (it rolled back).
     * @return the spend's result; every exception other than the deferral
     *         propagates untouched, from whichever run raised it.
     */
    public <T> T run(Supplier<T> spend) {
        // Nested scope: the outer run handles any deferral. Already inside a
        // transaction: deferring would mark the CALLER's transaction
        // rollback-only and the replay could never commit — keep the legacy
        // inline check instead.
        if (MODE.get() != null || TransactionSynchronizationManager.isActualTransactionActive()) {
            return spend.get();
        }
        MODE.set(Mode.DEFER);
        try {
            try {
                return spend.get();
            } catch (EligibilityCheckDeferred deferred) {
                resolve(deferred.phone());
            }
            MODE.set(Mode.REPLAY);
            return spend.get();
        } finally {
            MODE.remove();
        }
    }

    /**
     * Ask, and register a confirmed customer. Never throws: a registration that
     * cannot be written leaves the phone unregistered, and the replay then
     * refuses it {@code USER_PENDING} — the pre-existing answer.
     */
    private void resolve(String phone) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Unreachable: run() only opens a scope with no transaction open, and
            // every transaction the spend opened has ended by the time its
            // exception reaches here. If it ever is, do not make the remote call
            // under it — the replay refuses USER_PENDING, as on any outage.
            log.error("Eligibility deferral caught with a transaction still open; skipping the "
                    + "directory for phone={}", MsisdnMasking.mask(phone));
            return;
        }
        if (!eligibility.confirmsCustomer(phone)) {
            return;
        }
        try {
            users.registerPhone(phone, PhoneRegistration.Source.INNBUCKS_VALIDATE,
                    "on-demand-spend", null, null);
        } catch (RuntimeException ex) {
            log.warn("On-demand registration could not be written for phone={}; the spend is refused "
                    + "USER_PENDING and the sweeper converges the phone", MsisdnMasking.mask(phone), ex);
        }
    }
}
