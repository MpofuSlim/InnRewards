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
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 *   <li><b>Card machine</b> (V54) — a card swiped on the till's own terminal
 *       is confirmed the same way ({@link #confirmCardPos}), plus the
 *       terminal's approval code, and only in a currency the terminals settle
 *       in. It never touches payment-service either.</li>
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
    /** A terminal approval (authorisation) code: 4–12 letters or digits. */
    private static final Pattern APPROVAL_CODE = Pattern.compile("[A-Z0-9]{4,12}");
    private static final Pattern LAST4 = Pattern.compile("[0-9]{4}");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final VoucherPurchaseOrderRepository orders;
    private final VoucherRepository vouchers;
    private final VoucherService voucherService;
    private final MerchantAuthz merchantAuthz;
    private final SupportedCurrencies supportedCurrencies;
    private final ExchangeRateService fx;
    private final StaffRegistry staffRegistry;
    private final Duration orderTtl;
    private final Set<String> cardPosCurrencies;

    public VoucherPurchaseService(VoucherPurchaseOrderRepository orders,
                                  VoucherRepository vouchers,
                                  VoucherService voucherService,
                                  MerchantAuthz merchantAuthz,
                                  SupportedCurrencies supportedCurrencies,
                                  ExchangeRateService fx,
                                  StaffRegistry staffRegistry,
                                  LoyaltyProperties props) {
        this.orders = orders;
        this.vouchers = vouchers;
        this.voucherService = voucherService;
        this.merchantAuthz = merchantAuthz;
        this.supportedCurrencies = supportedCurrencies;
        this.fx = fx;
        this.staffRegistry = staffRegistry;
        this.orderTtl = props.voucher().purchaseOrderTtl();
        this.cardPosCurrencies = parseCurrencies(props.voucher().cardPosCurrencies());
    }

    /** Blank (or unbound, in a unit test) falls back to the ZW terminals' USD + ZWG. */
    static Set<String> parseCurrencies(String csv) {
        String source = csv == null || csv.isBlank() ? "USD,ZWG" : csv;
        return Arrays.stream(source.split(","))
                .map(c -> c.trim().toUpperCase(Locale.ROOT))
                .filter(c -> !c.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
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

        // The sender is whoever the request NAMES, or nobody — no fallback to
        // the caller's JWT phone. This path is staff-only
        // (MERCHANT_ADMIN/SHOP_ADMIN/SUPER_ADMIN), so that fallback resolved to
        // the CASHIER every time, and it fed the payer chain below: an order
        // created without an explicit sender pushed the EcoCash PIN prompt to
        // the cashier's own handset, asking a staff member to pay for a
        // customer's gift. The cashier's identity is recorded separately, as
        // issuer_* and cash_confirmed_by.
        // The sender may be the recipient (a voucher someone buys for
        // themselves): allowed, and worded as such when the voucher is issued.
        String senderPhone = firstNonBlank(req.senderPhone());
        // The payer: explicit, else the sender (the person gifting is usually
        // the person paying), else the recipient. EcoCash pushes its PIN prompt
        // to THIS number, so an order with no phone at all is not payable
        // electronically and is refused rather than minted broken.
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
        requireCallerMayActOn(tenantId, order);
        return toResponse(order);
    }

    /**
     * Staff confirmation of a CASH payment — the cashier's word IS the
     * payment proof, so it is gated on the same staff authz as issuing and
     * the confirmer's identity is recorded. Idempotent for a double-click on
     * an order already cash-confirmed; refused when the order was already
     * paid another way (the customer must not pay twice).
     */
    public Dtos.VoucherPurchaseOrderResponse confirmCash(UUID tenantId, String orderRef) {
        VoucherPurchaseOrder order = lockConfirmable(tenantId, orderRef);
        if (order.getStatus() == VoucherPurchaseOrder.Status.PAID) {
            if (order.getPaidVia() == VoucherPurchaseOrder.PaidVia.CASH) {
                return toResponse(order); // double-click replay
            }
            throw LoyaltyException.conflict("ORDER_ALREADY_PAID",
                    "This order was already paid " + paidHow(order) + " — do not take cash for it.");
        }
        requireOffSystemPayable(order, "take cash");
        requireShopStaffNotConfirmingForStaff(order);

        markPaid(order, VoucherPurchaseOrder.PaidVia.CASH, "CASH-" + UUID.randomUUID());
        order.setCashConfirmedBy(confirmerIdentity());
        issueForOrder(order);
        log.info("Voucher purchase order cash-confirmed orderRef={} by={} voucherId={}",
                order.getOrderRef(), order.getCashConfirmedBy(), order.getVoucherId());
        return toResponse(order);
    }

    /**
     * Staff confirmation of a card swiped on the TILL'S OWN card machine (V54).
     * The terminal is outside our systems, so — like cash — the cashier's
     * confirmation is the payment proof, with the same authz, double-payment
     * guards and expiry rule. Two things cash does not need:
     *
     * <ul>
     *   <li><b>The approval code from the terminal slip is required.</b> It is
     *       the one fact tying the order to a real card transaction, so finance
     *       can reconcile each voucher against the acquirer's settlement, and a
     *       "card" click with no swipe behind it has nothing to show.</li>
     *   <li><b>Only a currency the terminals settle in</b>
     *       ({@code loyalty.voucher.card-pos-currencies}). A terminal charges the
     *       number it is given in its own currency, so a ZAR 100 voucher swiped
     *       on a USD terminal would take USD 100 — the overcharge the electronic
     *       rails refuse for the same reason.</li>
     * </ul>
     *
     * A double-click replay with the SAME approval code returns the paid order;
     * a different code on an already card-paid order is refused, because it
     * means a second swipe.
     */
    public Dtos.VoucherPurchaseOrderResponse confirmCardPos(UUID tenantId, String orderRef,
                                                           String approvalCode, String last4) {
        String code = approvalCode == null ? "" : approvalCode.trim().toUpperCase(Locale.ROOT);
        if (!APPROVAL_CODE.matcher(code).matches()) {
            throw LoyaltyException.badRequest("APPROVAL_CODE_REQUIRED",
                    "Enter the approval code printed on the card machine slip (4 to 12 letters or digits).");
        }
        String cardLast4 = last4 == null || last4.isBlank() ? null : last4.trim();
        if (cardLast4 != null && !LAST4.matcher(cardLast4).matches()) {
            throw LoyaltyException.badRequest("INVALID_CARD_LAST4",
                    "last4 must be exactly the last four digits of the card, or left out.");
        }

        VoucherPurchaseOrder order = lockConfirmable(tenantId, orderRef);
        if (order.getStatus() == VoucherPurchaseOrder.Status.PAID) {
            if (order.getPaidVia() == VoucherPurchaseOrder.PaidVia.CARD_POS
                    && code.equals(order.getCardApprovalCode())) {
                return toResponse(order); // double-click replay
            }
            throw LoyaltyException.conflict("ORDER_ALREADY_PAID",
                    "This order was already paid " + paidHow(order) + " — do not charge the card.");
        }
        String currency = order.getCurrency() == null ? "" : order.getCurrency().toUpperCase(Locale.ROOT);
        if (!cardPosCurrencies.contains(currency)) {
            throw new LoyaltyException(HttpStatus.UNPROCESSABLE_ENTITY, "CARD_CURRENCY_UNSUPPORTED",
                    "The card machine can't take payment in " + currency + ". Take cash, or create the "
                            + "order in " + String.join(" or ", cardPosCurrencies.stream().sorted().toList())
                            + ".");
        }
        requireOffSystemPayable(order, "swipe the card");
        requireShopStaffNotConfirmingForStaff(order);

        markPaid(order, VoucherPurchaseOrder.PaidVia.CARD_POS, "CARD-" + code + "-" + UUID.randomUUID());
        order.setCardApprovalCode(code);
        order.setCardLast4(cardLast4);
        order.setCashConfirmedBy(confirmerIdentity());
        issueForOrder(order);
        log.info("Voucher purchase order card-confirmed orderRef={} by={} voucherId={}",
                order.getOrderRef(), order.getCashConfirmedBy(), order.getVoucherId());
        return toResponse(order);
    }

    /** Lock the order, pin it to the tenant (a foreign order is a plain 404)
     *  and apply the issue-grade staff authz — shared by every off-system
     *  confirmation so cash and card cannot drift apart. */
    private VoucherPurchaseOrder lockConfirmable(UUID tenantId, String orderRef) {
        VoucherPurchaseOrder order = orders.lockByOrderRef(orderRef)
                .filter(o -> o.getTenantId().equals(tenantId))
                .orElseThrow(() -> LoyaltyException.notFound("purchase order"));
        requireCallerMayActOn(tenantId, order);
        return order;
    }

    /**
     * Merchant authz, plus the OUTLET pin for shop staff: a caller whose token
     * names a shop (a cashier or shop admin) may read or confirm only orders
     * created at that shop. Cash taken at one till for an order raised at
     * another branch lands in a drawer nobody reconciles against it. An order
     * with no shop (raised by a merchant admin) stays reachable by the
     * merchant's staff. A foreign-shop order is a plain 404, the same answer
     * as a missing one.
     */
    private void requireCallerMayActOn(UUID tenantId, VoucherPurchaseOrder order) {
        merchantAuthz.requireCallerAdministersMerchant(tenantId, order.getMerchantId());
        UUID callerShop = CallerDetails.currentShopId();
        if (callerShop != null && order.getShopId() != null && !callerShop.equals(order.getShopId())
                && !CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN")) {
            throw LoyaltyException.notFound("purchase order");
        }
    }

    /**
     * Shop staff (cashiers and shop admins) may sell vouchers at the till, but
     * may not VOUCH for an off-system payment on a voucher that goes to staff.
     * A cash or card-machine confirmation is the cashier's word, so without this
     * a cashier could raise an order to their own phone, or a colleague's, press
     * "cash received" with nothing in the drawer, and walk away with a voucher.
     * Refused when the recipient is on the merchant's staff list, or when the
     * caller's own phone is the recipient, the sender or the payer. The customer
     * can still pay electronically (EcoCash / InnBucks move real money), or a
     * merchant admin can confirm. Merchant admins and platform staff are not
     * subject to it.
     *
     * <p>The staff list fails open during a user-service outage (see
     * {@link StaffRegistry}); the caller's-own-phone check needs no network and
     * holds throughout.
     */
    private void requireShopStaffNotConfirmingForStaff(VoucherPurchaseOrder order) {
        if (!isShopStaffOnly()) {
            return;
        }
        String callerPhone = CallerDetails.currentPhoneNumber();
        if (voucherService.samePhone(callerPhone, order.getAssigneePhone())
                || voucherService.samePhone(callerPhone, order.getSenderPhone())
                || voucherService.samePhone(callerPhone, order.getPayerPhone())) {
            log.warn("Off-system confirmation refused: shop staff on their own voucher orderRef={} by={}",
                    order.getOrderRef(), confirmerIdentity());
            throw LoyaltyException.forbidden("SELF_CONFIRM",
                    "You can't confirm a cash or card payment for a voucher to or from your own phone. "
                            + "Pay by EcoCash or InnBucks, or ask a merchant admin to confirm it.");
        }
        if (staffRegistry.isStaffPhone(order.getMerchantId(), order.getAssigneePhone())) {
            log.warn("Off-system confirmation refused: recipient is merchant staff orderRef={} by={}",
                    order.getOrderRef(), confirmerIdentity());
            throw LoyaltyException.forbidden("STAFF_RECIPIENT",
                    "This voucher is for a staff member, so shop staff can't confirm a cash or card payment "
                            + "for it. Pay by EcoCash or InnBucks, or ask a merchant admin to confirm it.");
        }
    }

    /** Shop staff (cashier / shop admin) not also holding an admin role — the
     *  callers {@link #requireShopStaffNotConfirmingForStaff} applies to. One
     *  definition, shared with {@link #prewarmStaffGuard}, so they cannot drift. */
    private static boolean isShopStaffOnly() {
        return CallerDetails.hasAnyRole("ROLE_SHOP_USER", "ROLE_SHOP_ADMIN")
                && !CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN", "ROLE_MERCHANT_ADMIN");
    }

    /**
     * Pre-load the STAFF_RECIPIENT registry for an order a cashier is about to
     * confirm (cash or card machine), BEFORE the confirm takes the order's row
     * lock — the guard runs under that lock, and a cold cache held it across a
     * user-service round-trip. A plain, non-locking read; only for the callers
     * the guard applies to and an order of this tenant with a recipient phone.
     * <b>Never throws and never refuses</b> — every refusal (404, authz,
     * SELF_CONFIRM, STAFF_RECIPIENT, …) is still the confirm's own, in its
     * existing order. Fail-open semantics are the registry's, unchanged.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void prewarmStaffGuard(UUID tenantId, String orderRef) {
        try {
            if (tenantId == null || orderRef == null || !isShopStaffOnly()) {
                return;
            }
            orders.findByOrderRef(orderRef)
                    .filter(o -> tenantId.equals(o.getTenantId()))
                    .filter(o -> o.getAssigneePhone() != null && !o.getAssigneePhone().isBlank())
                    .map(VoucherPurchaseOrder::getMerchantId)
                    .filter(merchantId -> !staffRegistry.isCached(merchantId))
                    .ifPresent(staffRegistry::warm);
        } catch (RuntimeException e) {
            // An optimisation only — the confirm loads on demand.
        }
    }

    /** Who confirmed an off-system payment: email, else phone, else account id.
     *  Cashier accounts may have no email; the record must still name them. */
    private static String confirmerIdentity() {
        String email = CallerDetails.currentEmail();
        if (email != null && !email.isBlank()) return email;
        String phone = CallerDetails.currentPhoneNumber();
        if (phone != null && !phone.isBlank()) return phone;
        UUID userId = CallerDetails.currentUserId();
        return userId == null ? null : userId.toString();
    }

    /**
     * The guards every off-system payment shares, after the paid-already
     * check: not cancelled, no electronic payment still live, not expired.
     */
    private void requireOffSystemPayable(VoucherPurchaseOrder order, String action) {
        if (order.getStatus() == VoucherPurchaseOrder.Status.CANCELLED) {
            throw LoyaltyException.conflict("ORDER_NOT_CONFIRMABLE",
                    "This order was cancelled and can no longer be paid.");
        }
        // An EcoCash prompt, InnBucks code or card checkout for this order may
        // still be completed by the customer. Taking money another way now is
        // how a customer pays twice: their late approval lands on an
        // already-paid order, is refused (ORDER_ALREADY_PAID) and becomes a
        // manual refund. Refused until the instrument's window (instrument TTL
        // + margin, recorded by extend-expiry) has passed; the console shows the
        // countdown.
        Instant now = Instant.now();
        if (order.electronicPaymentPending(now)) {
            long minutes = Math.max(1, (Duration.between(now, order.getElectronicPaymentUntil()).getSeconds() + 59) / 60);
            throw LoyaltyException.conflict("ELECTRONIC_PAYMENT_PENDING",
                    "An EcoCash, InnBucks or card payment for this order is still waiting for the customer. "
                            + "Don't " + action + " now: let the customer finish paying, or wait about " + minutes
                            + " minute" + (minutes == 1 ? "" : "s") + " for it to lapse and try again.");
        }
        // Unlike a LATE electronic confirmation (money already moved), this
        // money is being taken NOW — an expired order is simply re-created, so
        // refusing costs nothing and keeps the amount/FX snapshot fresh.
        if (!order.payable(now)) {
            throw LoyaltyException.conflict("ORDER_EXPIRED",
                    "This purchase order has expired — create a new one and take payment again.");
        }
    }

    private static String paidHow(VoucherPurchaseOrder order) {
        if (order.getPaidVia() == null) return "";
        return switch (order.getPaidVia()) {
            case GATEWAY -> "electronically";
            case CASH -> "in cash";
            case CARD_POS -> "on the card machine";
        };
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
        // payment-service is about to show the customer an instrument that
        // lives this long. Record it: confirm-cash must not run while the
        // customer can still pay electronically. Never shortened, like the
        // expiry itself — an earlier, longer instrument may still be live.
        if (order.getElectronicPaymentUntil() == null || candidate.isAfter(order.getElectronicPaymentUntil())) {
            order.setElectronicPaymentUntil(candidate);
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
        return internalConfirmPayment(orderRef, paymentRef, amountCents, null);
    }

    /** @param paymentRail the rail payment-service collected on (V56), recorded for
     *        the voucher report's payment-type filter; null when not sent. */
    public InternalOrderView internalConfirmPayment(String orderRef, String paymentRef, long amountCents,
                                                    String paymentRail) {
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
        order.setPaymentRail(cleanRail(paymentRail));
        issueForOrder(order);
        log.info("Voucher purchase order confirmed orderRef={} paymentRef={} voucherId={}",
                order.getOrderRef(), paymentRef, order.getVoucherId());
        return internalViewOf(order);
    }

    // ------------------------------------------------------------------

    /** payment-service's PaymentRail name, upper-cased; anything else (blank, too
     *  long, not a plain name) is dropped rather than refusing a paid confirm. */
    static String cleanRail(String raw) {
        if (raw == null) return null;
        String r = raw.strip().toUpperCase(java.util.Locale.ROOT);
        return r.matches("[A-Z0-9_]{1,32}") ? r : null;
    }

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
                order.getPaidAt(), voucher, order.getElectronicPaymentUntil(),
                order.getCardApprovalCode());
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
