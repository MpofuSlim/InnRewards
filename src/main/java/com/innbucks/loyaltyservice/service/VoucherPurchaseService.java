package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.VoucherPurchaseOrderRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Voucher purchase orders (V47): a voucher is PAID FOR before it exists.
 *
 * <p>{@link #create} snapshots one issue request (validated exactly as a
 * direct issue would be, so a paid order can't later fail on its own inputs)
 * plus the money to collect and the payer's phone, while the staff caller's
 * JWT is still present. The voucher itself is minted only at confirmation:
 *
 * <ul>
 *   <li><b>Electronic rails</b> — payment-service collects via EcoCash
 *       (PIN prompt), the InnBucks 2D code/QR or card, through its
 *       LOYALTY_VOUCHER order gateway, and calls the internal
 *       {@code confirm-payment} here. Idempotent by {@code paymentRef};
 *       the cents cross-check is the 100x guard's confirm leg.</li>
 *   <li><b>Cash</b> — a staff caller who took the money confirms it here
 *       ({@link #confirmCash}); their identity is recorded. Cash never
 *       touches payment-service.</li>
 * </ul>
 *
 * <p>Expiry is LAZY (see {@link VoucherPurchaseOrder}): nothing is reserved
 * by an order, so an expired row just stops being payable/extendable. A LATE
 * electronic confirmation is still honoured — by the time confirm-payment
 * arrives the customer's money has moved, and refusing it would strand a
 * paid customer for the sake of a bookkeeping deadline. Only CANCELLED (and
 * paid-under-a-different-reference) confirmations are refused; those park on
 * payment-service's operator queue.
 */
@Service
@Transactional
@Slf4j
public class VoucherPurchaseService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final VoucherPurchaseOrderRepository orders;
    private final VoucherRepository vouchers;
    private final VoucherService voucherService;
    private final MerchantAuthz merchantAuthz;
    private final SupportedCurrencies supportedCurrencies;
    private final ExchangeRateService fx;
    private final Duration orderTtl;

    public VoucherPurchaseService(VoucherPurchaseOrderRepository orders,
                                  VoucherRepository vouchers,
                                  VoucherService voucherService,
                                  MerchantAuthz merchantAuthz,
                                  SupportedCurrencies supportedCurrencies,
                                  ExchangeRateService fx,
                                  LoyaltyProperties props) {
        this.orders = orders;
        this.vouchers = vouchers;
        this.voucherService = voucherService;
        this.merchantAuthz = merchantAuthz;
        this.supportedCurrencies = supportedCurrencies;
        this.fx = fx;
        this.orderTtl = props.voucher().purchaseOrderTtl();
    }

    public Dtos.VoucherPurchaseOrderResponse create(UUID tenantId, Dtos.PurchaseVoucherRequest req) {
        // Same object-level authz as a direct issue: the caller must
        // administer the merchant the voucher (and the money) belongs to.
        Merchant merchant = merchantAuthz.requireCallerAdministersMerchant(
                tenantId, CallerDetails.resolveMerchantId(req.merchantId()));

        // Validate EVERYTHING issue would, NOW — a refusal must land on the
        // staff caller creating the order, never on a customer who already
        // paid. resolveUsageLimit enforces the SINGLE/MULTI contract;
        // requireSupported fails closed on the currency; the FX probe fails
        // closed on a supported-but-rateless currency (NO_FX_RATE) so the
        // eventual issue cannot be refused for a rate that was never there.
        if (req.value() == null || req.value().signum() <= 0) {
            throw LoyaltyException.badRequest("MISSING_VALUE",
                    "A voucher's face value must be a positive amount.");
        }
        // The rails collect whole CENTS; a sub-cent face value could never be
        // charged exactly, so it is refused HERE, on the staff caller — never
        // as a confirm-time explosion after the customer paid.
        if (req.value().stripTrailingZeros().scale() > 2) {
            throw LoyaltyException.badRequest("AMOUNT_PRECISION",
                    "A purchasable voucher's value cannot carry fractions of a cent.");
        }
        int usageLimit = VoucherService.resolveUsageLimit(req.voucherType(), req.usageLimit());
        String currency = supportedCurrencies.requireSupported(
                req.currency() != null && !req.currency().isBlank() ? req.currency() : merchant.getCurrency());
        fx.toBaseWithRate(tenantId, req.value(), currency);

        // The payer: explicit, else the sender (the person gifting is usually
        // the person paying — and senderPhone itself defaults to the caller's
        // JWT phone, same as issue), else the recipient. EcoCash pushes its
        // PIN prompt to THIS number, so an order with no phone at all is not
        // payable electronically and is refused rather than minted broken.
        String senderPhone = firstNonBlank(req.senderPhone(), CallerDetails.currentPhoneNumber());
        String payerPhone = firstNonBlank(req.payerPhone(), senderPhone, req.assigneePhone());
        if (payerPhone == null) {
            throw LoyaltyException.badRequest("PAYER_PHONE_REQUIRED",
                    "Provide payerPhone (or a senderPhone/assigneePhone to default from) — "
                            + "the payment prompt has to reach a real phone.");
        }

        VoucherPurchaseOrder order = new VoucherPurchaseOrder();
        order.setOrderRef(newOrderRef());
        order.setTenantId(tenantId);
        order.setMerchantId(merchant.getId());
        order.setAmount(req.value());
        order.setCurrency(currency);
        order.setPayerPhone(payerPhone);
        order.setVoucherType(VoucherService.voucherTypeOrDefault(req.voucherType()));
        order.setUsageLimit(usageLimit);
        order.setAssigneePhone(req.assigneePhone());
        order.setAssigneeName(HtmlSanitizer.stripAll(req.assigneeName()));
        order.setAssignedUserId(req.assignedUserId());
        order.setSenderName(HtmlSanitizer.stripAll(req.senderName()));
        order.setSenderPhone(senderPhone);
        order.setDeliveryChannel(req.deliveryChannel());
        order.setCampaignSource(req.campaignSource());
        // Issuer identity, snapshotted while the JWT is present — the
        // confirmation arrives S2S with no caller context.
        order.setShopId(CallerDetails.currentShopId());
        order.setIssuerUserId(CallerDetails.currentUserId());
        order.setIssuerPhone(CallerDetails.currentPhoneNumber());
        order.setIssuerEmail(CallerDetails.currentEmail());
        order.setExpiresAt(Instant.now().plus(orderTtl));
        orders.save(order);

        log.info("Voucher purchase order created orderRef={} tenantId={} merchantId={} amount={} {} payer={}",
                order.getOrderRef(), tenantId, merchant.getId(), order.getAmount(),
                order.getCurrency(), MsisdnMasking.mask(payerPhone));
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public Dtos.VoucherPurchaseOrderResponse get(UUID tenantId, String orderRef) {
        VoucherPurchaseOrder order = requireTenantOrder(tenantId, orderRef);
        merchantAuthz.requireCallerAdministersMerchant(tenantId, order.getMerchantId());
        return toResponse(order);
    }

    /**
     * Staff confirmation of a CASH payment — the cashier's word IS the
     * payment proof, so it is gated on the same staff authz as issuing and
     * the confirmer's identity is recorded. Idempotent for a double-click on
     * an order already cash-confirmed; refused when the order was already
     * paid electronically (the customer must not pay twice).
     */
    public Dtos.VoucherPurchaseOrderResponse confirmCash(UUID tenantId, String orderRef) {
        VoucherPurchaseOrder order = orders.lockByOrderRef(orderRef)
                .filter(o -> o.getTenantId().equals(tenantId))
                .orElseThrow(() -> LoyaltyException.notFound("purchase order"));
        merchantAuthz.requireCallerAdministersMerchant(tenantId, order.getMerchantId());

        if (order.getStatus() == VoucherPurchaseOrder.Status.PAID) {
            if (order.getPaidVia() == VoucherPurchaseOrder.PaidVia.CASH) {
                return toResponse(order); // double-click replay
            }
            throw LoyaltyException.conflict("ORDER_ALREADY_PAID",
                    "This order was already paid electronically — do not take cash for it.");
        }
        if (order.getStatus() == VoucherPurchaseOrder.Status.CANCELLED) {
            throw LoyaltyException.conflict("ORDER_NOT_CONFIRMABLE",
                    "This order was cancelled and can no longer be paid.");
        }
        // Unlike a LATE electronic confirmation (money already moved), cash is
        // being taken NOW — an expired order is simply re-created, so refusing
        // costs nothing and keeps the amount/FX snapshot fresh.
        if (!order.payable(Instant.now())) {
            throw LoyaltyException.conflict("ORDER_EXPIRED",
                    "This purchase order has expired — create a new one and take payment again.");
        }

        markPaid(order, VoucherPurchaseOrder.PaidVia.CASH, "CASH-" + UUID.randomUUID());
        order.setCashConfirmedBy(CallerDetails.currentEmail());
        issueForOrder(order);
        log.info("Voucher purchase order cash-confirmed orderRef={} by={} voucherId={}",
                order.getOrderRef(), order.getCashConfirmedBy(), order.getVoucherId());
        return toResponse(order);
    }

    // ------------------------------------------------------------------
    // Internal S2S surface (payment-service's LOYALTY_VOUCHER gateway)
    // ------------------------------------------------------------------

    /** Snapshot for payment-service's order fetch. */
    public record InternalOrderView(String orderRef, String status, BigDecimal amount,
                                    String currency, String payerMsisdn, Instant expiresAt,
                                    boolean payable) {}

    @Transactional(readOnly = true)
    public InternalOrderView internalView(String orderRef) {
        VoucherPurchaseOrder order = requireOrder(orderRef);
        return new InternalOrderView(order.getOrderRef(), effectiveStatus(order).name(),
                order.getAmount(), order.getCurrency(), order.getPayerPhone(),
                order.getExpiresAt(), order.payable(Instant.now()));
    }

    /**
     * Extend the order's payable window so it provably outlives the payment
     * instrument payment-service is about to show the customer. Never
     * shortens; refuses an order that is no longer payable (the console
     * creates a fresh order instead — nothing was reserved by the old one).
     */
    public InternalOrderView internalExtendExpiry(String orderRef, int minutes) {
        if (minutes < 1 || minutes > 60) {
            throw LoyaltyException.badRequest("INVALID_EXTENSION",
                    "extend-expiry minutes must be between 1 and 60, got " + minutes);
        }
        VoucherPurchaseOrder order = orders.lockByOrderRef(orderRef)
                .orElseThrow(() -> LoyaltyException.notFound("purchase order"));
        if (!order.payable(Instant.now())) {
            throw LoyaltyException.conflict("ORDER_NOT_EXTENDABLE",
                    "This purchase order is no longer awaiting payment.");
        }
        Instant candidate = Instant.now().plus(Duration.ofMinutes(minutes));
        if (candidate.isAfter(order.getExpiresAt())) {
            order.setExpiresAt(candidate);
        }
        return new InternalOrderView(order.getOrderRef(), effectiveStatus(order).name(),
                order.getAmount(), order.getCurrency(), order.getPayerPhone(),
                order.getExpiresAt(), true);
    }

    /**
     * Payment-service confirmed an electronic payment. Idempotent by
     * {@code paymentRef} (a same-ref replay answers 200 with the already
     * issued voucher); a DIFFERENT reference on a paid order is a 409 the
     * gateway parks for an operator. {@code amountCents} is cross-checked
     * against the order's frozen amount — the 100x guard's confirm leg.
     *
     * <p>An order past its expiry but never cancelled is CONFIRMED, not
     * refused: the money has already moved, and the order held its snapshot
     * (nothing was released at expiry), so issuing is strictly better than
     * stranding a paid customer on an operator queue.
     */
    public InternalOrderView internalConfirmPayment(String orderRef, String paymentRef, long amountCents) {
        if (paymentRef == null || paymentRef.isBlank()) {
            throw LoyaltyException.badRequest("PAYMENT_REF_REQUIRED",
                    "confirm-payment requires a non-blank paymentRef");
        }
        VoucherPurchaseOrder order = orders.lockByOrderRef(orderRef)
                .orElseThrow(() -> LoyaltyException.notFound("purchase order"));

        if (order.getStatus() == VoucherPurchaseOrder.Status.PAID) {
            if (paymentRef.equals(order.getPaymentRef())) {
                return internalViewOf(order); // idempotent replay
            }
            throw LoyaltyException.conflict("ORDER_ALREADY_PAID",
                    "This purchase order was already paid under a different reference.");
        }
        if (order.getStatus() == VoucherPurchaseOrder.Status.CANCELLED) {
            throw LoyaltyException.conflict("ORDER_NOT_CONFIRMABLE",
                    "This purchase order was cancelled.");
        }
        // Exact by construction: create() refuses sub-cent face values.
        long expectedCents = order.getAmount().movePointRight(2).longValueExact();
        if (amountCents != expectedCents) {
            throw new LoyaltyException(HttpStatus.UNPROCESSABLE_ENTITY, "AMOUNT_MISMATCH",
                    "Paid amount " + amountCents + "c does not match the order's " + expectedCents + "c");
        }

        markPaid(order, VoucherPurchaseOrder.PaidVia.GATEWAY, paymentRef);
        issueForOrder(order);
        log.info("Voucher purchase order confirmed orderRef={} paymentRef={} voucherId={}",
                order.getOrderRef(), paymentRef, order.getVoucherId());
        return internalViewOf(order);
    }

    // ------------------------------------------------------------------

    private void markPaid(VoucherPurchaseOrder order, VoucherPurchaseOrder.PaidVia via, String paymentRef) {
        order.setStatus(VoucherPurchaseOrder.Status.PAID);
        order.setPaidVia(via);
        order.setPaymentRef(paymentRef);
        order.setPaidAt(Instant.now());
    }

    /** Issue in the SAME transaction as the PAID flip: if the issue throws,
     *  the whole confirmation rolls back and the caller retries — the row can
     *  never say PAID with no voucher behind it. */
    private void issueForOrder(VoucherPurchaseOrder order) {
        Dtos.VoucherResponse voucher = voucherService.issueFromOrder(order);
        order.setVoucherId(voucher.id());
    }

    private VoucherPurchaseOrder requireOrder(String orderRef) {
        return orders.findByOrderRef(orderRef)
                .orElseThrow(() -> LoyaltyException.notFound("purchase order"));
    }

    private VoucherPurchaseOrder requireTenantOrder(UUID tenantId, String orderRef) {
        return orders.findByOrderRef(orderRef)
                .filter(o -> o.getTenantId().equals(tenantId))
                .orElseThrow(() -> LoyaltyException.notFound("purchase order"));
    }

    /** PENDING_PAYMENT past its deadline reads as EXPIRED (lazy expiry). */
    private static VoucherPurchaseOrder.Status effectiveStatus(VoucherPurchaseOrder order) {
        if (order.getStatus() == VoucherPurchaseOrder.Status.PENDING_PAYMENT
                && !order.payable(Instant.now())) {
            return VoucherPurchaseOrder.Status.EXPIRED;
        }
        return order.getStatus();
    }

    private InternalOrderView internalViewOf(VoucherPurchaseOrder order) {
        return new InternalOrderView(order.getOrderRef(), effectiveStatus(order).name(),
                order.getAmount(), order.getCurrency(), order.getPayerPhone(),
                order.getExpiresAt(), order.payable(Instant.now()));
    }

    private Dtos.VoucherPurchaseOrderResponse toResponse(VoucherPurchaseOrder order) {
        Dtos.VoucherResponse voucher = order.getVoucherId() == null ? null
                : vouchers.findById(order.getVoucherId()).map(VoucherService::toResponse).orElse(null);
        return new Dtos.VoucherPurchaseOrderResponse(
                order.getOrderRef(), effectiveStatus(order).name(),
                order.getAmount(), order.getCurrency(), order.getPayerPhone(),
                order.getExpiresAt(),
                order.getPaidVia() == null ? null : order.getPaidVia().name(),
                order.getPaidAt(), voucher);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** VCH-<12 uppercase hex> — the marketplace MKT- ref shape. */
    private static String newOrderRef() {
        StringBuilder sb = new StringBuilder("VCH-");
        for (int i = 0; i < 12; i++) {
            sb.append(HEX[RANDOM.nextInt(16)]);
        }
        return sb.toString();
    }
}
