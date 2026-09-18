package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
            redemptions.save(r);

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
        }
    }
}
