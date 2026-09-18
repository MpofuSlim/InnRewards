package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Persists the evidence of a REFUSED voucher redemption after the refusal's
 * transaction has rolled back.
 *
 * <p>{@link VoucherRedemptionRejectedEvent} carries the full reasoning for why
 * this is a post-rollback listener rather than a {@code REQUIRES_NEW} call at
 * the refusal site: the foreign key from {@code voucher_redemptions} to
 * {@code vouchers} needs a lock on the row the refusing transaction has locked
 * for write, so writing inline from a second transaction would hang the redeem
 * instead of failing it.
 *
 * <p>Deliberately NOT {@code @Async}. This is the error path, not the hot path,
 * and an audit row that may or may not have been written by the time the
 * response leaves is worth less than one that certainly has.
 */
@Component
@Slf4j
public class VoucherRedemptionAuditWriter {

    private final VoucherRedemptionRepository redemptions;
    private final VoucherRepository vouchers;

    public VoucherRedemptionAuditWriter(VoucherRedemptionRepository redemptions,
                                        VoucherRepository vouchers) {
        this.redemptions = redemptions;
        this.vouchers = vouchers;
    }

    /**
     * {@code REQUIRES_NEW} because AFTER_ROLLBACK runs with no transaction in
     * scope — there is nothing left to join.
     *
     * <p>Nothing in here is allowed to escape. Spring already swallows an
     * exception thrown from a completion-phase listener, but relying on that
     * would make "a failed audit write must never change what the customer was
     * told" an accident of framework behaviour rather than a decision. A refusal
     * that could not be recorded is logged at WARN and still a refusal.
     *
     * <p><b>Which is why the save is a {@code saveAndFlush} and the catch marks
     * the transaction rollback-only.</b> Written the obvious way the promise
     * above was not kept: {@code VoucherRedemption.id} is {@code @GeneratedValue}
     * on a UUID, so Hibernate assigns it in memory and {@code save} issues no
     * SQL at all — the INSERT is deferred to the flush, which happens when this
     * {@code REQUIRES_NEW} transaction commits, inside the transaction
     * interceptor and therefore OUTSIDE the try. A constraint violation (an
     * over-long {@code outletCode}, say) could not reach the catch, so the
     * documented WARN never fired and a stack trace escaped to the framework
     * instead. Flushing inside the try is what puts the failure back within
     * reach of the handler that claims to handle it. Marking the status
     * rollback-only then lets the interceptor roll back QUIETLY: a
     * {@code setRollbackOnly()} on this transaction's own status is a LOCAL
     * rollback, which does not raise {@code UnexpectedRollbackException} the way
     * a globally-marked participating transaction would.
     *
     * <p><b>The expiry flip shares that transaction and is therefore lost with
     * it</b> when the audit row cannot be written. That is deliberate rather
     * than overlooked: the flip is idempotent, safe to lose and converged by the
     * expiry sweeper, whereas splitting it into a second transaction to save it
     * would buy a guaranteed status update at the cost of a second failure mode
     * on an error path that must stay simple. What is NOT acceptable is the
     * customer's answer changing, and that never depended on either write —
     * the refusal was returned before this listener ran.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRedemptionRejected(VoucherRedemptionRejectedEvent e) {
        try {
            VoucherRedemption r = new VoucherRedemption();
            r.setTenantId(e.tenantId());
            r.setVoucherId(e.voucherId());
            r.setUserId(e.userId());
            r.setMerchantId(e.merchantId());
            r.setOutletCode(e.outletCode());
            r.setIpAddress(e.ipAddress());
            r.setDeviceFingerprint(e.deviceFingerprint());
            r.setResult(VoucherRedemption.Result.REJECTED);
            r.setReason(e.reason());
            redemptions.saveAndFlush(r);

            if (e.markExpired()) {
                // Guarded and idempotent: the UPDATE re-checks the deadline and
                // only moves a still-live voucher, so it can never write a
                // status that was not already true, and can never clobber a
                // REDEEMED or REVOKED transition that landed in between.
                int moved = vouchers.markExpiredIfDue(e.voucherId());
                if (moved > 0) {
                    log.info("Voucher {} flipped to EXPIRED on a redemption attempt past its deadline",
                            e.voucherId());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Could not record the rejected redemption of voucher {} (reason={})",
                    e.voucherId(), e.reason(), ex);
            rollBackQuietly();
        }
    }

    /**
     * Abandon the audit transaction without letting anything out. A failed
     * flush leaves the persistence context in an undefined state, so the only
     * safe next step is to discard it — and doing that explicitly is what stops
     * the interceptor attempting a commit that would throw on the way out,
     * past the catch above.
     *
     * <p>Tolerates having no transaction in scope so a unit test can call the
     * listener directly, which is the only way to test it without Docker.
     */
    private void rollBackQuietly() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ignored) {
            // Called outside a transaction (a unit test): there is nothing to
            // roll back, and the WARN above has already been emitted.
        }
    }
}
