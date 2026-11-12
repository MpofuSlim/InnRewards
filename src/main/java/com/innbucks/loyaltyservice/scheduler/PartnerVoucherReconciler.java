package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.config.VoucherPartnerProperties;
import com.innbucks.loyaltyservice.repository.VoucherPurchaseOrderRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedeemPaymentRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.service.partner.PartnerVoucherService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Finishes partner voucher money legs nobody is waiting on (V62): a generate
 * whose payment is still open (an InnBucks code the customer pays later, a leg
 * the provider has not answered), a funded order whose voucher could not be
 * issued yet, a redemption whose amount has not yet been paid to the
 * merchant, and an agent's payment for a code paid at the redeem (V69) that the
 * provider has not answered, or that confirmed before its voucher was recorded. Without it, a voucher would appear only when the partner happened
 * to inquire.
 *
 * <p>Each item is resolved by ASKING the provider (a read) or settling once
 * through the provider's idempotency key; nothing is ever sent twice. Every
 * item is its own short transaction, and a failure on one is logged and does
 * not stop the rest. ShedLock keeps two replicas from doing the same work at
 * once; if a lock lapses, the funding writes are row-locked and compare their
 * state first, and a settlement is marked only from PENDING, so a race changes
 * nothing.
 */
@Component
public class PartnerVoucherReconciler {

    private static final Logger log = LoggerFactory.getLogger(PartnerVoucherReconciler.class);
    static final int BATCH = 100;

    private final VoucherPartnerProperties props;
    private final VoucherPurchaseOrderRepository orders;
    private final VoucherRedemptionRepository redemptions;
    private final VoucherRedeemPaymentRepository payments;
    private final PartnerVoucherService service;

    public PartnerVoucherReconciler(VoucherPartnerProperties props, VoucherPurchaseOrderRepository orders,
                                    VoucherRedemptionRepository redemptions, VoucherRedeemPaymentRepository payments,
                                    PartnerVoucherService service) {
        this.props = props;
        this.orders = orders;
        this.redemptions = redemptions;
        this.payments = payments;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${loyalty.voucher-partner.reconcile-interval:PT15S}",
            initialDelayString = "${loyalty.voucher-partner.reconcile-interval:PT15S}")
    @SchedulerLock(name = "partnerVoucherReconcile", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void reconcile() {
        if (!props.enabled()) {
            return;
        }
        List<UUID> openOrders = orders.findOpenPartnerFundingIds(PageRequest.of(0, BATCH));
        for (UUID id : openOrders) {
            try {
                service.resolveOpen(id);
            } catch (RuntimeException e) {
                log.warn("Partner order reconcile failed orderId={}", id, e);
            }
        }
        List<UUID> pending = redemptions.findPendingSettlementIds(PageRequest.of(0, BATCH));
        for (UUID id : pending) {
            service.trySettle(id);
        }
        List<UUID> openPayments = payments.findOpenIds(PageRequest.of(0, BATCH));
        for (UUID id : openPayments) {
            try {
                service.resolvePayment(id);
            } catch (RuntimeException e) {
                log.warn("Payment at redeem reconcile failed paymentId={}", id, e);
            }
        }
        if (!openOrders.isEmpty() || !pending.isEmpty() || !openPayments.isEmpty()) {
            log.info("Partner voucher reconcile: {} open orders, {} pending settlements, {} open payments at redeem",
                    openOrders.size(), pending.size(), openPayments.size());
        }
    }
}
