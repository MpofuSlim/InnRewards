package com.innbucks.loyaltyservice.service.partner;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.config.VoucherPartnerProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PartnerVoucherDtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherPartner;
import com.innbucks.loyaltyservice.entity.VoucherPartnerMerchant;
import com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder;
import com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.FundingStatus;
import com.innbucks.loyaltyservice.entity.VoucherRedeemPayment;
import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.exception.LoyaltyDataException;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.exception.VoucherCodeGuessException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherPartnerMerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherPurchaseOrderRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedeemPaymentRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.PartnerRequest;
import com.innbucks.loyaltyservice.service.ExchangeRateService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.VoucherGuessGuard;
import com.innbucks.loyaltyservice.service.VoucherPartnershipService;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.partner.VoucherFundingProvider.FundingLeg;
import com.innbucks.loyaltyservice.service.partner.VoucherFundingProvider.FundingOutcome;
import com.innbucks.loyaltyservice.util.AfterCommit;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import com.innbucks.loyaltyservice.util.VoucherCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The partner voucher API (V62): generate, inquire and redeem for a merchant a
 * partner (the InnBucks middleware) is linked to.
 *
 * <p><b>Hold until redeemed.</b> A generate is a voucher purchase order (V47)
 * whose payment is a FUNDING LEG into the holding account. The voucher is issued
 * only once that leg is CONFIRMED. Each redemption owes its amount to the
 * merchant, paid from holding by a SETTLEMENT leg after the use commits. Both
 * legs go through {@link VoucherFundingProvider}; on staging that provider is
 * simulated.
 *
 * <p><b>Transaction shape.</b> No provider call ever runs inside a database
 * transaction. A generate claims its order (one transaction, which is also the
 * idempotency check: the partner's reference is unique per merchant), marks the
 * leg PENDING (a second), calls the provider with none open, then records the
 * outcome (a third) and issues the voucher (a fourth, so a failed issue leaves a
 * CONFIRMED order the reconciler retries, never a lost payment).
 *
 * <p><b>Retries.</b> The same reference with the same content replays the order
 * as it now stands. A leg is started only from NOT_STARTED, so a retry never
 * starts a second one; an open leg is resolved by asking the provider for its
 * status, which is what the reconciler does too.
 *
 * <p><b>Paid at the redeem (Flow 2, V69).</b> A generate with no {@code funding}
 * gives the customer a code and moves no money. The customer pays it in full at
 * one of the issuer's agents, whose redeem carries the payment: one leg from the
 * agent's account straight to the issuer ({@link VoucherFundingProvider.Destination#MERCHANT}),
 * recorded as a {@link VoucherRedeemPayment} attempt with the same shape as a
 * funding leg (PENDING before the call, never sent twice). There is no voucher
 * until that payment confirms: the code waits on its order, and the voucher is
 * then created and spent in one transaction
 * ({@link VoucherService#issueAndSpendAtRedeem}). So an unpaid code is never in
 * holding, a voucher total, an expiry warning or a fee.
 */
@Service
@Slf4j
public class PartnerVoucherService {

    /** Every funding method the API names. */
    static final Set<String> METHODS = Set.of("CASH", "CARD_SWIPE", "MERCHANT_COLLECTED", "INNBUCKS_CODE",
            "INNBUCKS_ACCOUNT", "ECOCASH");
    /** The methods this build can fund. The account debit waits on the provider being able to
     *  scope it; EcoCash is phase 2. */
    static final Set<String> AVAILABLE_METHODS = Set.of("CASH", "CARD_SWIPE", "MERCHANT_COLLECTED", "INNBUCKS_CODE");
    /** Money the merchant collected itself: its float is debited into holding. */
    static final Set<String> MERCHANT_COLLECTED_METHODS = Set.of("CASH", "CARD_SWIPE", "MERCHANT_COLLECTED");
    static final Set<String> COLLECTED_VIA = Set.of("ECOCASH_MERCHANT", "BANK_TRANSFER", "BETTING_WALLET", "OTHER");
    /** Where a sale or a redemption happens. APP is the provider's own app (2026-11-12). */
    static final Set<String> CHANNELS = Set.of("POS", "ONLINE", "APP");
    /** The funding view's status for a code that is paid at the redeem and not paid yet (V69). */
    static final String AT_REDEEM = "AT_REDEEM";
    /** Refusal codes of a code paid at the redeem (V69). */
    public static final String PAYMENT_REQUIRED = "PAYMENT_REQUIRED";
    public static final String PAYMENT_NOT_NEEDED = "PAYMENT_NOT_NEEDED";
    public static final String PAYMENT_IN_PROGRESS = "PAYMENT_IN_PROGRESS";
    /** {@code redeemableAt} value meaning every partner of the issuer. Never a merchant code. */
    public static final String ANY = "ANY";
    /** At most this many merchants in a voucher's redeemableAt (V68). */
    static final int MAX_REDEEMABLE_AT = 20;

    private static final Pattern APPROVAL_CODE = Pattern.compile("[A-Z0-9]{4,12}");
    private static final Pattern LAST4 = Pattern.compile("[0-9]{4}");
    private static final Pattern LINK_LIKE = Pattern.compile(
            "(?i)(https?:|www\\.|\\b[a-z0-9-]+\\.(com|net|org|info|biz|io|co|zw|me|ly|link|xyz)\\b)");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final VoucherPartnerMerchantRepository links;
    private final VoucherPartnershipService partnerships;
    private final MerchantRepository merchants;
    private final VoucherPurchaseOrderRepository orders;
    private final VoucherRepository vouchers;
    private final VoucherRedemptionRepository redemptions;
    private final VoucherRedeemPaymentRepository payments;
    private final NotificationGateway notifications;
    private final VoucherService voucherService;
    private final VoucherFundingProvider provider;
    private final VoucherPartnerProperties props;
    private final SupportedCurrencies supportedCurrencies;
    private final ExchangeRateService fx;
    private final UserService userService;
    private final VoucherGuessGuard guard;
    private final LoyaltyMetrics metrics;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Duration orderTtl;
    private final Clock clock;

    public PartnerVoucherService(VoucherPartnerMerchantRepository links, MerchantRepository merchants,
                                 VoucherPurchaseOrderRepository orders, VoucherRepository vouchers,
                                 VoucherRedemptionRepository redemptions, VoucherService voucherService,
                                 VoucherFundingProvider provider, VoucherPartnerProperties props,
                                 SupportedCurrencies supportedCurrencies, ExchangeRateService fx,
                                 UserService userService, VoucherGuessGuard guard, LoyaltyMetrics metrics,
                                 PlatformTransactionManager txManager, LoyaltyProperties loyaltyProps,
                                 VoucherPartnershipService partnerships, VoucherRedeemPaymentRepository payments,
                                 NotificationGateway notifications) {
        this.links = links;
        this.partnerships = partnerships;
        this.merchants = merchants;
        this.orders = orders;
        this.vouchers = vouchers;
        this.redemptions = redemptions;
        this.payments = payments;
        this.notifications = notifications;
        this.voucherService = voucherService;
        this.provider = provider;
        this.props = props;
        this.supportedCurrencies = supportedCurrencies;
        this.fx = fx;
        this.userService = userService;
        this.guard = guard;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.orderTtl = loyaltyProps.voucher().purchaseOrderTtl();
        this.clock = Clock.systemUTC();
    }

    /** The HTTP answer to a generate: status, envelope code and message, and the result. */
    public record Answer<T>(HttpStatus status, String code, String message, T data) {}

    // ================================================================= generate

    public Answer<PartnerVoucherDtos.GenerateResult> generate(PartnerRequest caller,
                                                              PartnerVoucherDtos.GenerateRequest req) {
        VoucherPartner partner = caller.partner();
        Linked linked = resolve(partner, req.merchant());
        Sale sale = validate(linked, req);

        if (sale.validityDays() == null
                && !voucherService.wouldExpire(linked.merchant().getTenantId(), linked.merchant())) {
            // A held voucher that never expired would hold money for ever.
            throw new LoyaltyException(HttpStatus.SERVICE_UNAVAILABLE, "MERCHANT_NOT_READY",
                    "This merchant is not set up for vouchers yet. Nothing was recorded.");
        }
        if (!provider.available()) {
            throw new LoyaltyException(HttpStatus.SERVICE_UNAVAILABLE, "FUNDING_UNAVAILABLE",
                    "Voucher payments aren't available right now. Nothing was recorded; send the same request "
                            + "again later.");
        }

        VoucherPurchaseOrder existing = readTx.execute(s ->
                orders.findByMerchantIdAndPartnerReference(linked.merchant().getId(), sale.reference()).orElse(null));
        if (existing != null) {
            return replay(existing, sale, linked);
        }

        List<UUID> redeemableAt = sale.payAtRedeem() ? List.of() : resolveRedeemableAt(linked, sale.redeemable());
        requireWithinLimits(linked, sale);
        if (sale.evidenceKey() != null && Boolean.TRUE.equals(readTx.execute(s -> payments
                .existsByMerchantIdAndEvidenceKeyAndStatusNot(linked.merchant().getId(), sale.evidenceKey(),
                        VoucherRedeemPayment.Status.FAILED)))) {
            // Already paid for a code at the redeem (V69): one slip, one payment.
            throw evidenceUsed(sale.method());
        }

        UUID orderId;
        try {
            orderId = tx.execute(s -> orders.saveAndFlush(newOrder(partner, linked, sale, redeemableAt)).getId());
        } catch (DataIntegrityViolationException clash) {
            String detail = String.valueOf(clash.getMostSpecificCause().getMessage());
            if (detail.contains("uq_vpo_partner_reference")) {
                // A concurrent request with the same reference won the claim.
                VoucherPurchaseOrder winner = readTx.execute(s -> orders.findByMerchantIdAndPartnerReference(
                        linked.merchant().getId(), sale.reference()).orElseThrow());
                return replay(winner, sale, linked);
            }
            if (detail.contains("uq_vpo_funding_evidence")) {
                throw evidenceUsed(sale.method());
            }
            throw clash;
        }
        if (sale.payAtRedeem()) {
            announceCode(orderId);
        } else {
            drive(orderId);
        }
        return generateAnswer(load(orderId), linked, false);
    }

    private static LoyaltyException evidenceUsed(String method) {
        return "CARD_SWIPE".equals(method)
                ? LoyaltyException.conflict("APPROVAL_CODE_ALREADY_USED",
                        "This card approval code already paid for another voucher.")
                : LoyaltyException.conflict("COLLECTION_REF_ALREADY_USED",
                        "This collection reference already paid for another voucher.");
    }

    /** Sends a new Flow 2 code to its customer (V69), once, after its order committed. */
    private void announceCode(UUID orderId) {
        VoucherPurchaseOrder o = load(orderId);
        notifications.notifyPayAtRedeemCode(new NotificationGateway.PayAtRedeemCode(o.getId(), o.getMerchantId(),
                o.getAssigneePhone(), o.getReservedCode(), o.getAmount(), o.getCurrency(), o.getExpiresAt()));
    }

    private Answer<PartnerVoucherDtos.GenerateResult> replay(VoucherPurchaseOrder existing, Sale sale, Linked linked) {
        if (!sale.fingerprint().equals(existing.getRequestFingerprint())) {
            throw new LoyaltyException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_MISMATCH",
                    "This partnerReference was already used for a different request.");
        }
        if (existing.isPayAtRedeem()) {
            resolvePaymentsFor(existing.getId());
        } else if (existing.getFundingStatus() == FundingStatus.NOT_STARTED) {
            drive(existing.getId());
        } else {
            resolveOpen(existing.getId());
        }
        return generateAnswer(load(existing.getId()), linked, true);
    }

    /** Starts the funding leg of an order that has none yet, and settles what it can. */
    void drive(UUID orderId) {
        FundingLeg leg = tx.execute(s -> {
            VoucherPurchaseOrder o = orders.lockById(orderId).orElseThrow();
            if (o.getFundingStatus() != FundingStatus.NOT_STARTED || o.isPayAtRedeem()) {
                // A code paid at the redeem has no funding leg at generate (V69).
                return null;
            }
            // Persisted BEFORE the call: a crash mid-call leaves PENDING, which the
            // reconciler resolves by asking the provider, never by sending again.
            o.setFundingStatus(FundingStatus.PENDING);
            o.setFundingStartedAt(clock.instant());
            return legOf(o);
        });
        if (leg == null) {
            return;
        }
        FundingOutcome outcome;
        try {
            outcome = provider.fund(leg);
        } catch (RuntimeException unknown) {
            log.warn("Partner funding call failed with no outcome orderId={}; the reconciler will ask for its status",
                    orderId, unknown);
            outcome = FundingOutcome.pending();
        }
        applyOutcome(orderId, outcome);
        issueIfFunded(orderId);
    }

    /** Asks the provider where an open leg stands, and issues the voucher once it is funded. */
    public void resolveOpen(UUID orderId) {
        FundingLeg leg = readTx.execute(s -> orders.findById(orderId)
                .filter(o -> o.getFundingStatus() == FundingStatus.PENDING
                        || o.getFundingStatus() == FundingStatus.AWAITING)
                .map(this::legOf)
                .orElse(null));
        if (leg != null) {
            FundingOutcome outcome;
            try {
                outcome = provider.status(leg);
            } catch (RuntimeException unreadable) {
                log.warn("Partner funding status unreadable orderId={}", orderId, unreadable);
                outcome = FundingOutcome.pending();
            }
            // A status read can only settle a leg; NOT_SENT means nothing here.
            if (outcome.kind() == FundingOutcome.Kind.CONFIRMED || outcome.kind() == FundingOutcome.Kind.FAILED) {
                applyOutcome(orderId, outcome);
            }
        }
        issueIfFunded(orderId);
    }

    private void applyOutcome(UUID orderId, FundingOutcome outcome) {
        tx.executeWithoutResult(s -> {
            VoucherPurchaseOrder o = orders.lockById(orderId).orElseThrow();
            if (o.getFundingStatus() != FundingStatus.PENDING && o.getFundingStatus() != FundingStatus.AWAITING) {
                return; // already settled by another path
            }
            switch (outcome.kind()) {
                case CONFIRMED -> {
                    o.setFundingStatus(FundingStatus.CONFIRMED);
                    o.setFundingRef(outcome.providerRef());
                    markPaid(o, outcome.providerRef());
                    metrics.incPartnerRequest("funding", "confirmed");
                }
                case FAILED, NOT_SENT -> {
                    o.setFundingStatus(FundingStatus.FAILED);
                    o.setFundingFailureCode(outcome.failureCode() == null ? "PAYMENT_DECLINED" : outcome.failureCode());
                    o.setStatus(VoucherPurchaseOrder.Status.CANCELLED);
                    metrics.incPartnerRequest("funding", "failed");
                }
                case AWAITING -> {
                    o.setFundingStatus(FundingStatus.AWAITING);
                    if (outcome.paymentCode() != null) {
                        o.setPaymentCode(outcome.paymentCode());
                        o.setPaymentExpiresAt(outcome.paymentExpiresAt());
                    }
                    if (outcome.paymentExpiresAt() != null
                            && outcome.paymentExpiresAt().plus(Duration.ofMinutes(5)).isAfter(o.getExpiresAt())) {
                        o.setExpiresAt(outcome.paymentExpiresAt().plus(Duration.ofMinutes(5)));
                    }
                }
                case PENDING -> { }
            }
        });
    }

    /** Issues the voucher of a funded order, in its own transaction. A failure
     *  leaves the order CONFIRMED with no voucher, which the reconciler retries. */
    private void issueIfFunded(UUID orderId) {
        try {
            tx.executeWithoutResult(s -> {
                VoucherPurchaseOrder o = orders.lockById(orderId).orElseThrow();
                if (o.getFundingStatus() != FundingStatus.CONFIRMED || o.getVoucherId() != null
                        || o.isPayAtRedeem()) {
                    // A Flow 2 voucher is created only with its payment (completePayment).
                    return;
                }
                Dtos.VoucherResponse voucher = voucherService.issueFromOrder(o);
                o.setVoucherId(voucher.id());
                log.info("Partner voucher issued orderRef={} partnerReference={} voucherId={}",
                        o.getOrderRef(), o.getPartnerReference(), voucher.id());
            });
        } catch (RuntimeException issueFailed) {
            log.error("Partner order funded but its voucher could not be issued yet orderId={}; will retry",
                    orderId, issueFailed);
        }
    }

    /** Off-system methods keep their till names in reports; the rest are electronic. */
    private void markPaid(VoucherPurchaseOrder o, String providerRef) {
        o.setStatus(VoucherPurchaseOrder.Status.PAID);
        o.setPaidAt(clock.instant());
        o.setPaymentRef(trim(providerRef == null ? "PARTNER-" + o.getId() : providerRef, 64));
        switch (o.getFundingMethod()) {
            case "CASH" -> o.setPaidVia(VoucherPurchaseOrder.PaidVia.CASH);
            case "CARD_SWIPE" -> o.setPaidVia(VoucherPurchaseOrder.PaidVia.CARD_POS);
            default -> {
                o.setPaidVia(VoucherPurchaseOrder.PaidVia.GATEWAY);
                o.setPaymentRail(o.getFundingMethod());
            }
        }
    }

    private Answer<PartnerVoucherDtos.GenerateResult> generateAnswer(VoucherPurchaseOrder o, Linked linked,
                                                                    boolean replayed) {
        PartnerVoucherDtos.GenerateResult result = toResult(o, linked.link().getMerchantCode(), replayed);
        metrics.incPartnerRequest("generate", result.status().toLowerCase(Locale.ROOT));
        return switch (result.status()) {
            case "ISSUED" -> replayed
                    ? new Answer<>(HttpStatus.OK, "200 OK", "Voucher issued", result)
                    : new Answer<>(HttpStatus.CREATED, "201 CREATED", "Voucher issued", result);
            case "AWAITING_PAYMENT" -> new Answer<>(HttpStatus.ACCEPTED, "202 ACCEPTED",
                    "Waiting for the customer to pay", result);
            case "FAILED" -> "PROVIDER_UNAVAILABLE".equals(o.getFundingFailureCode())
                    ? new Answer<>(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                            "The payment provider could not be reached. Nothing moved; use a new partnerReference "
                                    + "to try again.", result)
                    : new Answer<>(HttpStatus.UNPROCESSABLE_ENTITY, o.getFundingFailureCode(),
                            failureMessage(o.getFundingFailureCode()), result);
            case "EXPIRED" -> new Answer<>(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_EXPIRED",
                    failureMessage("PAYMENT_EXPIRED"), result);
            default -> new Answer<>(HttpStatus.ACCEPTED, "202 ACCEPTED", "Payment is being processed", result);
        };
    }

    static String failureMessage(String code) {
        return switch (code == null ? "" : code) {
            case "FLOAT_INSUFFICIENT" -> "The merchant's float can't cover this sale. Nothing was charged.";
            case "INSUFFICIENT_FUNDS" -> "The customer's account doesn't have enough money. Nothing was charged.";
            case "PAYMENT_EXPIRED" -> "The customer didn't pay in time. Nothing was charged.";
            case "PROVIDER_LIMIT_REACHED" -> "The payment provider refused this amount. Nothing was charged.";
            default -> "The payment did not go through. Nothing was charged.";
        };
    }

    // =================================================================== inquire

    public Answer<Object> inquire(PartnerRequest caller, PartnerVoucherDtos.InquireRequest req) {
        VoucherPartner partner = caller.partner();
        Linked linked = resolve(partner, req.merchant());
        UUID merchantId = linked.merchant().getId();
        String by = req.by() == null ? "" : req.by().strip().toUpperCase(Locale.ROOT);
        metrics.incPartnerRequest("inquire", by.toLowerCase(Locale.ROOT));
        return switch (by) {
            case "PARTNER_REFERENCE" -> {
                String ref = required("partnerReference", req.partnerReference());
                VoucherPurchaseOrder o = readTx.execute(s -> orders.findByMerchantIdAndPartnerReference(merchantId, ref)
                        .filter(found -> partner.getId().equals(found.getPartnerId()))
                        .orElse(null));
                if (o == null) {
                    throw LoyaltyException.notFound("order");
                }
                if (o.isPayAtRedeem()) {
                    resolvePaymentsFor(o.getId());
                } else {
                    resolveOpen(o.getId());
                }
                yield new Answer<>(HttpStatus.OK, "200 OK", "Order found",
                        toResult(load(o.getId()), linked.link().getMerchantCode(), false));
            }
            case "CODE" -> {
                String code = required("code", req.code());
                String endUserRef = required("endUserRef", req.endUserRef());
                VoucherService.PartnerRedeemScope scope = scope(linked, endUserRef, req.endUserPhone(),
                        "ONLINE", req.terminalId(), null);
                PartnerVoucherDtos.VoucherBalanceView view = guard.attemptAs(
                        guardIdentity(partner, merchantId, endUserRef), () -> {
                            VoucherPurchaseOrder unpaid = unpaidPayAtRedeem(partner, linked, code, scope);
                            if (unpaid != null) {
                                resolvePaymentsFor(unpaid.getId());
                                VoucherPurchaseOrder now = load(unpaid.getId());
                                if (now.getVoucherId() == null) {
                                    return unpaidBalance(now);
                                }
                            }
                            return balanceOf(voucherService.inquireForPartner(linked.merchant().getTenantId(),
                                    merchantId, code, scope));
                        });
                yield new Answer<>(HttpStatus.OK, "200 OK", "Voucher found", view);
            }
            case "REDEMPTION" -> {
                String ref = required("reference", req.reference());
                VoucherRedemption r = findRedemption(partner, merchantId, ref);
                if (r == null) {
                    // A payment at the redeem (V69) that has not become a use yet.
                    VoucherRedeemPayment p = readTx.execute(s -> payments.findByMerchantIdAndReference(merchantId, ref)
                            .filter(found -> partner.getId().equals(found.getPartnerId()))
                            .orElse(null));
                    if (p == null) {
                        throw LoyaltyException.notFound("redemption");
                    }
                    resolvePayment(p.getId());
                    VoucherRedeemPayment now = readTx.execute(s -> payments.findById(p.getId()).orElseThrow());
                    if (now.getRedemptionId() == null) {
                        yield new Answer<>(HttpStatus.OK, "200 OK", "Redemption found", unfinishedResult(now, true));
                    }
                    r = readTx.execute(s -> redemptions.findById(now.getRedemptionId()).orElseThrow());
                }
                if (r.getSettlementStatus() == VoucherRedemption.SettlementStatus.PENDING) {
                    trySettle(r.getId());
                    r = findRedemption(partner, merchantId, ref);
                }
                yield new Answer<>(HttpStatus.OK, "200 OK", "Redemption found", redeemResultOf(r, true));
            }
            default -> throw validation("by", "must be PARTNER_REFERENCE, CODE or REDEMPTION");
        };
    }

    // ==================================================================== redeem

    /**
     * Redeems through {@link VoucherService#redeemForPartnerIdempotent}, under the
     * guessing lockout keyed by partner, merchant and the customer's account on
     * the partner's platform. The controller wraps this call in
     * {@code EligibilityDeferral.run}, as every spend entry point must be; a
     * partner redemption never reaches the holder's account gate, so nothing is
     * ever deferred.
     *
     * <p>A code paid at the redeem (Flow 2, V69) is paid here instead: the
     * redeem must carry {@code funding}, the payment is claimed under the
     * lockout, then sent with no transaction open (and never twice), and the
     * voucher is created and spent once it confirms. Any other voucher was paid
     * for when it was sold, so a redeem that carries {@code funding} for one is
     * refused: a second payment for it is never taken.
     */
    public Answer<PartnerVoucherDtos.RedeemResult> redeem(PartnerRequest caller, PartnerVoucherDtos.RedeemRequest req) {
        VoucherPartner partner = caller.partner();
        Linked linked = resolve(partner, req.merchant());
        UUID merchantId = linked.merchant().getId();
        String channel = req.channel() == null || req.channel().isBlank()
                ? "ONLINE" : req.channel().strip().toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(channel)) {
            throw validation("channel", "must be POS, ONLINE or APP");
        }
        String currency = req.currency() == null || req.currency().isBlank()
                ? null : req.currency().strip().toUpperCase(Locale.ROOT);
        String reference = req.reference().strip();
        VoucherService.PartnerRedeemScope scope = scope(linked, req.endUserRef().strip(), req.endUserPhone(),
                channel, blankToNull(req.terminalId()), currency);

        // A re-sent payment at the redeem answers with that payment as it now stands.
        VoucherRedeemPayment prior = readTx.execute(s -> payments.findByMerchantIdAndReference(merchantId, reference)
                .filter(p -> partner.getId().equals(p.getPartnerId()))
                .orElse(null));
        if (prior != null) {
            return replayPayment(prior, req, currency);
        }

        Routed routed = guard.attemptAs(guardIdentity(partner, merchantId, scope.endUserRef()), () -> {
            VoucherPurchaseOrder unpaid = unpaidPayAtRedeem(partner, linked, req.code(), scope);
            if (unpaid != null) {
                return new Routed(claimPayment(linked, unpaid, req, channel, currency, scope, reference), null);
            }
            if (req.funding() != null) {
                // Look the code up first, so an unknown one is still a counted miss.
                Voucher v = voucherService.inquireForPartner(linked.merchant().getTenantId(), merchantId,
                        req.code(), scope);
                if (v.getStatus() == Voucher.Status.REDEEMED
                        || (v.getRemainingBalance() != null && v.getRemainingBalance().signum() <= 0)) {
                    throw LoyaltyException.conflict("ALREADY_REDEEMED", "This voucher has already been fully redeemed.");
                }
                throw LoyaltyException.badRequest(PAYMENT_NOT_NEEDED,
                        "This voucher was paid for when it was sold. Redeem it without funding.");
            }
            Dtos.RedeemVoucherRequest redeem = new Dtos.RedeemVoucherRequest(merchantId, req.code(), null, null,
                    null, null, req.amount(), reference);
            return new Routed(null, voucherService.redeemForPartnerIdempotent(linked.merchant().getTenantId(),
                    merchantId, redeem, scope));
        });

        if (routed.paymentId() != null) {
            executePayment(routed.paymentId());
            return paymentAnswer(routed.paymentId(), false);
        }
        Dtos.RedemptionResponse used = routed.used();
        trySettle(used.redemptionId());
        VoucherRedemption r = readTx.execute(s -> redemptions.findById(used.redemptionId()).orElseThrow());
        metrics.incPartnerRequest("redeem", used.replayed() ? "replayed" : "redeemed");
        return new Answer<>(HttpStatus.OK, "200 OK", "Voucher redeemed", redeemResultOf(r, used.replayed()));
    }

    /** Which way a redeem went: a Flow 2 payment claimed, or an ordinary use made. */
    private record Routed(UUID paymentId, Dtos.RedemptionResponse used) {}

    /** Pays one partner use's amount from holding to the merchant, once. Never throws. */
    public void trySettle(UUID redemptionId) {
        try {
            VoucherFundingProvider.SettlementLeg leg = readTx.execute(s -> redemptions.findById(redemptionId)
                    .filter(r -> r.getSettlementStatus() == VoucherRedemption.SettlementStatus.PENDING)
                    .map(r -> new VoucherFundingProvider.SettlementLeg(r.getId(), r.getMerchantId(), r.getAmount(),
                            vouchers.findById(r.getVoucherId()).map(Voucher::getCurrency).orElse(null),
                            r.getReference()))
                    .orElse(null));
            if (leg == null) {
                return;
            }
            VoucherFundingProvider.SettlementOutcome outcome = provider.settle(leg);
            if (outcome.settled()) {
                tx.executeWithoutResult(s -> redemptions.markSettled(redemptionId,
                        trim(outcome.providerRef(), 100), clock.instant()));
            }
        } catch (RuntimeException e) {
            log.warn("Partner settlement not made yet redemptionId={}; will retry", redemptionId, e);
        }
    }

    // ============================================ Flow 2: payment at the redeem (V69)

    /**
     * The partner's unpaid Flow 2 code, or null when {@code code} is not one. A
     * code of this partner's at another merchant is NOT_REDEEMABLE_HERE (a Flow 2
     * code is paid to its issuer only), and one the link binds to the holder's
     * phone is refused another person's phone; both counted like an unknown code.
     */
    private VoucherPurchaseOrder unpaidPayAtRedeem(VoucherPartner partner, Linked linked, String typed,
                                                  VoucherService.PartnerRedeemScope scope) {
        String code = VoucherCodes.normalize(typed);
        if (code == null || code.isBlank()) {
            return null;
        }
        VoucherPurchaseOrder o = readTx.execute(s -> orders.findByReservedCode(code)
                .filter(found -> found.isPayAtRedeem() && found.getVoucherId() == null)
                .filter(found -> partner.getId().equals(found.getPartnerId()))
                .orElse(null));
        if (o == null) {
            return null;
        }
        if (!o.getMerchantId().equals(linked.merchant().getId())) {
            throw VoucherService.notRedeemableHere();
        }
        if (scope.bindToHolderPhone() && !voucherService.samePhone(scope.endUserPhone(), o.getAssigneePhone())) {
            throw VoucherCodeGuessException.from(LoyaltyException.forbidden("NOT_VOUCHER_OWNER",
                    "This voucher isn't assigned to you."));
        }
        return o;
    }

    /** The agent's payment for a Flow 2 code, checked: how the money was taken, and its evidence. */
    private record AgentPayment(String method, String accountNumber, Evidence evidence) {}

    private AgentPayment agentPayment(Linked linked, String channel, String terminalId,
                                      PartnerVoucherDtos.Funding f) {
        if (f == null) {
            throw LoyaltyException.badRequest(PAYMENT_REQUIRED,
                    "This voucher is paid at the redeem: send funding, how the agent took the customer's money.");
        }
        String method = f.method().strip().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw validation("funding.method",
                    "must be one of " + String.join(", ", MERCHANT_COLLECTED_METHODS.stream().sorted().toList()));
        }
        // The agent takes the money itself and its float pays the issuer.
        if (!MERCHANT_COLLECTED_METHODS.contains(method) || !fitsChannel(method, channel)
                || !merchantAccepts(linked.link(), method)) {
            throw LoyaltyException.badRequest("FUNDING_METHOD_NOT_ALLOWED",
                    "This payment method is not available here.");
        }
        Evidence evidence = evidence(method, f, terminalId, null);
        return new AgentPayment(method, blankToNull(f.accountNumber()), evidence);
    }

    /** What a payment at the redeem is for: the same request sent again matches it. Validates nothing. */
    private static String paymentFingerprint(PartnerVoucherDtos.RedeemRequest req, String currency) {
        PartnerVoucherDtos.Funding f = req.funding();
        return sha256(String.join("|", nz(VoucherCodes.normalize(req.code())),
                req.amount() == null ? "FULL" : req.amount().stripTrailingZeros().toPlainString(), nz(currency),
                nz(req.endUserRef() == null ? null : req.endUserRef().strip()),
                f == null || f.method() == null ? "" : f.method().strip().toUpperCase(Locale.ROOT),
                f == null || f.approvalCode() == null ? "" : f.approvalCode().strip().toUpperCase(Locale.ROOT),
                f == null || f.collectionRef() == null ? "" : f.collectionRef().strip(),
                f == null || f.accountNumber() == null ? "" : f.accountNumber().strip()));
    }

    /**
     * Claims a payment for an unpaid Flow 2 code: every check, then one PENDING
     * attempt under the order's row lock. Nothing is sent yet. The checks run in
     * the order a fresh redeem's do: currency, expiry, then the amount (it must
     * be the full value: a Flow 2 code is SINGLE).
     */
    private UUID claimPayment(Linked linked, VoucherPurchaseOrder unpaid, PartnerVoucherDtos.RedeemRequest req,
                              String channel, String currency, VoucherService.PartnerRedeemScope scope,
                              String reference) {
        UUID merchantId = linked.merchant().getId();
        if (currency != null && !currency.equalsIgnoreCase(unpaid.getCurrency())) {
            throw LoyaltyException.badRequest("CURRENCY_MISMATCH",
                    "This voucher is in " + unpaid.getCurrency() + ", not " + currency + ".");
        }
        if (expired(unpaid)) {
            throw LoyaltyException.badRequest("EXPIRED", "This voucher has expired.");
        }
        BigDecimal amount = unpaid.getAmount();
        if (req.amount() != null && req.amount().compareTo(amount) < 0) {
            throw LoyaltyException.badRequest(VoucherService.PARTIAL_USE_NOT_ALLOWED,
                    "This voucher must be paid and used for its full value in one go.");
        }
        if (req.amount() != null && req.amount().compareTo(amount) > 0) {
            BigDecimal full = money2(amount);
            throw new LoyaltyDataException(HttpStatus.BAD_REQUEST, VoucherService.INSUFFICIENT_VOUCHER_BALANCE,
                    "This voucher is for " + unpaid.getCurrency() + " " + full.toPlainString() + ".",
                    Map.of("remainingBalance", full, "currency", unpaid.getCurrency()));
        }
        if (readTx.execute(s -> redemptions.findFirstByMerchantIdAndReferenceAndResult(merchantId, reference,
                VoucherRedemption.Result.SUCCESS).isPresent())) {
            throw LoyaltyException.conflict(VoucherService.DUPLICATE_REFERENCE,
                    "This reference was already used for another voucher redemption.");
        }
        AgentPayment payment = agentPayment(linked, channel, scope.terminalId(), req.funding());
        String fingerprint = paymentFingerprint(req, currency);
        try {
            return tx.execute(s -> {
                VoucherPurchaseOrder o = orders.lockById(unpaid.getId()).orElseThrow();
                if (o.getVoucherId() != null || o.getStatus() == VoucherPurchaseOrder.Status.PAID) {
                    throw LoyaltyException.conflict("ALREADY_REDEEMED", "This voucher has already been fully redeemed.");
                }
                if (payments.existsByOrderIdAndStatusIn(o.getId(),
                        List.of(VoucherRedeemPayment.Status.PENDING, VoucherRedeemPayment.Status.CONFIRMED))) {
                    throw paymentInProgress();
                }
                String key = payment.evidence().key();
                if (key != null && (orders.existsByMerchantIdAndFundingEvidenceKey(merchantId, key)
                        || payments.existsByMerchantIdAndEvidenceKeyAndStatusNot(merchantId, key,
                                VoucherRedeemPayment.Status.FAILED))) {
                    throw evidenceUsed(payment.method());
                }
                VoucherRedeemPayment p = new VoucherRedeemPayment();
                p.setOrderId(o.getId());
                p.setPartnerId(o.getPartnerId());
                p.setMerchantId(merchantId);
                p.setReference(reference);
                p.setRequestFingerprint(fingerprint);
                p.setEndUserRef(scope.endUserRef());
                p.setChannel(channel);
                p.setTerminalId(scope.terminalId());
                p.setFundingMethod(payment.method());
                p.setSourceAccount(payment.accountNumber());
                p.setCardApprovalCode(payment.evidence().approvalCode());
                p.setCardLast4(payment.evidence().last4());
                p.setCollectedVia(payment.evidence().collectedVia());
                p.setCollectionRef(payment.evidence().collectionRef());
                p.setEvidenceKey(key);
                p.setAmount(o.getAmount());
                p.setCurrency(o.getCurrency());
                // Persisted BEFORE the call: a crash mid-call leaves PENDING, which
                // the reconciler resolves by asking the provider, never by sending again.
                p.setStatus(VoucherRedeemPayment.Status.PENDING);
                p.setStartedAt(clock.instant());
                return payments.saveAndFlush(p).getId();
            });
        } catch (DataIntegrityViolationException clash) {
            String detail = String.valueOf(clash.getMostSpecificCause().getMessage());
            if (detail.contains("uq_vrp_reference")) {
                // The same reference, claimed a moment ago by a concurrent request.
                return readTx.execute(s -> payments.findByMerchantIdAndReference(merchantId, reference)
                        .orElseThrow()).getId();
            }
            if (detail.contains("uq_vrp_live_per_order")) {
                throw paymentInProgress();
            }
            if (detail.contains("uq_vrp_evidence")) {
                throw evidenceUsed(payment.method());
            }
            throw clash;
        }
    }

    private static LoyaltyException paymentInProgress() {
        return LoyaltyException.conflict(PAYMENT_IN_PROGRESS,
                "A payment for this voucher is already in progress. Check it with an inquiry by redemption, "
                        + "or try again shortly.");
    }

    /** Sends a claimed payment, once, with no transaction open, then records what it can. */
    void executePayment(UUID paymentId) {
        FundingLeg leg = readTx.execute(s -> payments.findById(paymentId)
                .filter(p -> p.getStatus() == VoucherRedeemPayment.Status.PENDING)
                .map(this::legOf)
                .orElse(null));
        if (leg != null) {
            FundingOutcome outcome;
            try {
                outcome = provider.fund(leg);
            } catch (RuntimeException unknown) {
                log.warn("Payment at redeem failed with no outcome paymentId={}; the reconciler will ask for its "
                        + "status", paymentId, unknown);
                outcome = FundingOutcome.pending();
            }
            applyPaymentOutcome(paymentId, outcome);
        }
        completePayment(paymentId);
    }

    /** Asks the provider where an open payment stands, and records the voucher once it has confirmed. Never sends. */
    public void resolvePayment(UUID paymentId) {
        FundingLeg leg = readTx.execute(s -> payments.findById(paymentId)
                .filter(p -> p.getStatus() == VoucherRedeemPayment.Status.PENDING)
                .map(this::legOf)
                .orElse(null));
        if (leg != null) {
            FundingOutcome outcome;
            try {
                outcome = provider.status(leg);
            } catch (RuntimeException unreadable) {
                log.warn("Payment at redeem status unreadable paymentId={}", paymentId, unreadable);
                outcome = FundingOutcome.pending();
            }
            // A status read can only settle a payment; NOT_SENT means nothing here.
            if (outcome.kind() == FundingOutcome.Kind.CONFIRMED || outcome.kind() == FundingOutcome.Kind.FAILED) {
                applyPaymentOutcome(paymentId, outcome);
            }
        }
        completePayment(paymentId);
    }

    /** {@link #resolvePayment} for a code's open payment, if it has one. */
    private void resolvePaymentsFor(UUID orderId) {
        readTx.execute(s -> payments.findFirstByOrderIdOrderByStartedAtDesc(orderId)
                        .filter(p -> p.getStatus() != VoucherRedeemPayment.Status.FAILED && p.getRedemptionId() == null)
                        .map(VoucherRedeemPayment::getId))
                .ifPresent(this::resolvePayment);
    }

    private void applyPaymentOutcome(UUID paymentId, FundingOutcome outcome) {
        tx.executeWithoutResult(s -> {
            VoucherRedeemPayment p = payments.lockById(paymentId).orElseThrow();
            if (p.getStatus() != VoucherRedeemPayment.Status.PENDING) {
                return; // already settled by another path
            }
            switch (outcome.kind()) {
                case CONFIRMED -> {
                    p.setStatus(VoucherRedeemPayment.Status.CONFIRMED);
                    p.setProviderRef(trim(outcome.providerRef(), 100));
                    p.setFinishedAt(clock.instant());
                    metrics.incPartnerRequest("pay_at_redeem", "confirmed");
                }
                case FAILED, NOT_SENT -> {
                    p.setStatus(VoucherRedeemPayment.Status.FAILED);
                    p.setFailureCode(outcome.failureCode() == null ? "PAYMENT_DECLINED" : outcome.failureCode());
                    p.setFinishedAt(clock.instant());
                    metrics.incPartnerRequest("pay_at_redeem", "failed");
                }
                case PENDING, AWAITING -> { }
            }
        });
    }

    /**
     * A CONFIRMED payment becomes its voucher, created and spent at once, in one
     * transaction with the order marked paid. A failure leaves the payment
     * CONFIRMED with no voucher, which the reconciler retries; the money has
     * already moved, so this must happen, and the order can never be paid twice
     * meanwhile (the unique index allows one live or confirmed payment per code).
     */
    private void completePayment(UUID paymentId) {
        try {
            tx.executeWithoutResult(s -> {
                VoucherRedeemPayment p = payments.lockById(paymentId).orElseThrow();
                if (p.getStatus() != VoucherRedeemPayment.Status.CONFIRMED || p.getRedemptionId() != null) {
                    return;
                }
                VoucherPurchaseOrder o = orders.lockById(p.getOrderId()).orElseThrow();
                if (o.getVoucherId() != null) {
                    log.error("Flow 2 order {} already has a voucher; payment {} confirmed too and needs an "
                            + "operator", o.getId(), p.getId());
                    return;
                }
                VoucherService.PartnerRedeemScope scope = new VoucherService.PartnerRedeemScope(p.getPartnerId(),
                        p.getEndUserRef(), null, p.getChannel(), p.getTerminalId(), false, p.getCurrency());
                VoucherRedemption r = voucherService.issueAndSpendAtRedeem(o, scope, p.getReference(),
                        p.getProviderRef() == null ? "PAY-" + p.getId() : p.getProviderRef());
                p.setRedemptionId(r.getId());
                o.setVoucherId(r.getVoucherId());
                o.setFundingMethod(p.getFundingMethod());
                o.setFundingStatus(FundingStatus.CONFIRMED);
                o.setFundingRef(p.getProviderRef());
                o.setFundingStartedAt(p.getStartedAt());
                if ("CARD_SWIPE".equals(p.getFundingMethod())) {
                    o.setCardApprovalCode(p.getCardApprovalCode());
                    o.setCardLast4(p.getCardLast4());
                }
                markPaid(o, p.getProviderRef());
                NotificationGateway.PaidAtRedeem receipt = new NotificationGateway.PaidAtRedeem(o.getId(),
                        o.getMerchantId(), o.getAssigneePhone(), o.getReservedCode(), o.getAmount(), o.getCurrency());
                AfterCommit.run(() -> notifications.notifyPaidAtRedeem(receipt));
                log.info("Flow 2 voucher paid at the redeem orderRef={} paymentId={} voucherId={}", o.getOrderRef(),
                        p.getId(), r.getVoucherId());
            });
        } catch (RuntimeException notYet) {
            log.error("Payment at redeem confirmed but its voucher could not be recorded yet paymentId={}; will retry",
                    paymentId, notYet);
        }
    }

    private FundingLeg legOf(VoucherRedeemPayment p) {
        return new FundingLeg(p.getId(), p.getReference(), p.getMerchantId(), p.getFundingMethod(), p.getAmount(),
                p.getCurrency(), null, p.getSourceAccount(), p.getStartedAt(), VoucherFundingProvider.Destination.MERCHANT);
    }

    /** The same reference again: the payment as it now stands, if it is the same request. */
    private Answer<PartnerVoucherDtos.RedeemResult> replayPayment(VoucherRedeemPayment prior,
                                                                 PartnerVoucherDtos.RedeemRequest req,
                                                                 String currency) {
        String code = readTx.execute(s -> orders.findById(prior.getOrderId())
                .map(VoucherPurchaseOrder::getReservedCode).orElse(null));
        if (code == null || !code.equals(VoucherCodes.normalize(req.code()))) {
            throw LoyaltyException.conflict(VoucherService.DUPLICATE_REFERENCE,
                    "This reference was already used for another voucher redemption.");
        }
        if (!prior.getRequestFingerprint().equals(paymentFingerprint(req, currency))) {
            throw new LoyaltyException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_MISMATCH",
                    "This reference was already used for a different redemption.");
        }
        resolvePayment(prior.getId());
        return paymentAnswer(prior.getId(), true);
    }

    /** The HTTP answer for a payment at the redeem, from where it now stands. */
    private Answer<PartnerVoucherDtos.RedeemResult> paymentAnswer(UUID paymentId, boolean replayed) {
        VoucherRedeemPayment p = readTx.execute(s -> payments.findById(paymentId).orElseThrow());
        metrics.incPartnerRequest("redeem", replayed ? "replayed" : "paid_at_redeem");
        if (p.getRedemptionId() != null) {
            VoucherRedemption r = readTx.execute(s -> redemptions.findById(p.getRedemptionId()).orElseThrow());
            return new Answer<>(HttpStatus.OK, "200 OK", "Voucher paid and redeemed",
                    redeemResultOf(r, replayed, fundingViewOf(p)));
        }
        PartnerVoucherDtos.RedeemResult result = unfinishedResult(p, replayed);
        if (p.getStatus() == VoucherRedeemPayment.Status.FAILED) {
            return "PROVIDER_UNAVAILABLE".equals(p.getFailureCode())
                    ? new Answer<>(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                            "The payment provider could not be reached. Nothing moved; use a new reference to try "
                                    + "again.", result)
                    : new Answer<>(HttpStatus.UNPROCESSABLE_ENTITY, p.getFailureCode(),
                            agentFailureMessage(p.getFailureCode()), result);
        }
        return new Answer<>(HttpStatus.ACCEPTED, "202 ACCEPTED", "Payment is being processed", result);
    }

    static String agentFailureMessage(String code) {
        return "FLOAT_INSUFFICIENT".equals(code)
                ? "The agent's float can't cover this payment. Nothing was taken."
                : failureMessage(code);
    }

    /** A payment that has not become a use yet: PENDING, or FAILED with its reason. */
    private PartnerVoucherDtos.RedeemResult unfinishedResult(VoucherRedeemPayment p, boolean replayed) {
        String status = p.getStatus() == VoucherRedeemPayment.Status.FAILED ? "FAILED" : "PENDING";
        return new PartnerVoucherDtos.RedeemResult(null, null, status, money2(p.getAmount()), money2(p.getAmount()),
                p.getCurrency(), null, p.getReference(), replayed, null, fundingViewOf(p));
    }

    private PartnerVoucherDtos.FundingView fundingViewOf(VoucherRedeemPayment p) {
        String status = p.getRedemptionId() != null ? "CONFIRMED"
                : p.getStatus() == VoucherRedeemPayment.Status.FAILED ? "FAILED" : "PENDING";
        return new PartnerVoucherDtos.FundingView(p.getFundingMethod(), status, p.getFailureCode(),
                provider.simulated());
    }

    // ================================================================== helpers

    /** The partner's link to the named merchant, which must be an escrow merchant. */
    record Linked(VoucherPartnerMerchant link, Merchant merchant) {}

    private Linked resolve(VoucherPartner partner, String merchantCode) {
        String code = merchantCode == null ? "" : merchantCode.strip().toUpperCase(Locale.ROOT);
        VoucherPartnerMerchant link = readTx.execute(s ->
                links.findByPartnerIdAndMerchantCode(partner.getId(), code).orElse(null));
        if (link == null || link.getStatus() != VoucherPartner.Status.ACTIVE) {
            throw LoyaltyException.forbidden("MERCHANT_NOT_PERMITTED", "This merchant is not set up for you.");
        }
        Merchant merchant = readTx.execute(s -> merchants.findById(link.getMerchantId()).orElse(null));
        if (merchant == null || !merchant.isEscrowMode()) {
            throw new LoyaltyException(HttpStatus.SERVICE_UNAVAILABLE, "MERCHANT_NOT_READY",
                    "This merchant is not set up for vouchers yet. Nothing was recorded.");
        }
        return new Linked(link, merchant);
    }

    /** A generate request, checked and normalised. */
    record Sale(String reference, String channel, String outlet, String terminalId, String operatorRef,
                String endUserRef, BigDecimal amount, String currency, String phone, String name, String method,
                String approvalCode, String last4, String collectedVia, String collectionRef, String payerPhone,
                String evidenceKey, String fingerprint, boolean confirmDuplicate, Integer validityDays,
                boolean singleRedemption, Redeemable redeemable, boolean payAtRedeem) {}

    /**
     * A generate's {@code redeemableAt}, normalised: {@code any} for "ANY", else the
     * codes named (trimmed, upper case, each once, in the order sent, the issuer's
     * own code left out because it is always included). Neither = the issuer only.
     */
    record Redeemable(boolean any, List<String> codes) {
        static final Redeemable ISSUER_ONLY = new Redeemable(false, List.of());

        /** What joins the idempotency fingerprint: nothing for the issuer only. */
        String fingerprintPart() {
            if (any) {
                return "|redeemableAt=ANY";
            }
            return codes.isEmpty() ? "" : "|redeemableAt=" + String.join(",", codes.stream().sorted().toList());
        }
    }

    private Sale validate(Linked linked, PartnerVoucherDtos.GenerateRequest req) {
        PartnerVoucherDtos.Funding f = req.funding();
        // No funding: the customer pays at the redeem (Flow 2, V69).
        boolean payAtRedeem = f == null;
        String channel = req.channel() == null || req.channel().isBlank()
                ? (payAtRedeem ? "ONLINE" : null) : req.channel().strip().toUpperCase(Locale.ROOT);
        if (channel == null) {
            throw validation("channel", "is required");
        }
        if (!CHANNELS.contains(channel)) {
            throw validation("channel", "must be POS, ONLINE or APP");
        }
        String method = null;
        if (!payAtRedeem) {
            method = f.method().strip().toUpperCase(Locale.ROOT);
            if (!METHODS.contains(method)) {
                throw validation("funding.method",
                        "must be one of " + String.join(", ", METHODS.stream().sorted().toList()));
            }
            if (!AVAILABLE_METHODS.contains(method) || !fitsChannel(method, channel)
                    || !merchantAccepts(linked.link(), method)) {
                throw LoyaltyException.badRequest("FUNDING_METHOD_NOT_ALLOWED",
                        "This payment method is not available here.");
            }
        }

        String outlet = channel.equals("POS") ? blankToNull(req.outlet()) : null;
        String terminalId = channel.equals("POS") ? blankToNull(req.terminalId()) : null;
        if (channel.equals("POS") && linked.link().isRequireTerminal() && terminalId == null) {
            throw LoyaltyException.forbidden("TERMINAL_NOT_REGISTERED", "Send the POS terminalId for this sale.");
        }

        BigDecimal amount = req.amount();
        if (amount.stripTrailingZeros().scale() > 2) {
            throw LoyaltyException.badRequest("AMOUNT_PRECISION",
                    "A voucher's value cannot carry fractions of a cent.");
        }
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal max = linked.link().getMaxValue() != null ? linked.link().getMaxValue() : props.maxValue();
        if (amount.compareTo(props.minValue()) < 0) {
            throw limit("MIN_VALUE", "The smallest voucher is " + money(props.minValue()) + ".");
        }
        if (amount.compareTo(max) > 0) {
            throw limit("MAX_VALUE", "The largest voucher is " + money(max) + ".");
        }

        String currency = req.currency().strip().toUpperCase(Locale.ROOT);
        if (!props.currencySet().contains(currency)) {
            throw LoyaltyException.badRequest("CURRENCY_NOT_FUNDABLE",
                    "Vouchers can't be paid for in " + currency + " yet.");
        }
        supportedCurrencies.requireSupported(currency);
        fx.toBaseWithRate(linked.merchant().getTenantId(), amount, currency);

        String phone;
        try {
            phone = userService.normalizePhone(req.customer().phone());
        } catch (RuntimeException notAPhone) {
            phone = null;
        }
        if (phone == null) {
            throw LoyaltyException.badRequest("BAD_PHONE",
                    "Give the customer's phone number, for example +263771234567.");
        }
        String name = cleanName(req.customer().name());

        Evidence evidence = payAtRedeem ? Evidence.NONE : evidence(method, f, terminalId, outlet);

        Integer validityDays = req.validityDays();
        if (validityDays != null
                && (validityDays < props.minValidityDays() || validityDays > props.maxValidityDays())) {
            throw validation("validityDays",
                    "must be between " + props.minValidityDays() + " and " + props.maxValidityDays() + " days");
        }

        // SINGLE is the default (owner decision, 2026-11-12), as in the partner guide.
        String usage = req.usage() == null || req.usage().isBlank()
                ? "SINGLE" : req.usage().strip().toUpperCase(Locale.ROOT);
        if (!usage.equals("SINGLE") && !usage.equals("MULTIPLE")) {
            throw validation("usage", "must be SINGLE or MULTIPLE");
        }
        boolean singleRedemption = usage.equals("SINGLE");

        Redeemable redeemable = redeemable(linked, req.redeemableAt());
        if (payAtRedeem) {
            // A code paid at the redeem is paid in full, to its issuer, by the
            // issuer's agent: one payment, so SINGLE; and the issuer is the one
            // paid, so it is redeemed nowhere else.
            if (!singleRedemption) {
                throw LoyaltyException.badRequest("USAGE_NOT_ALLOWED",
                        "A voucher paid at the redeem is paid in full in one go: send usage SINGLE or leave it out.");
            }
            if (redeemable.any() || !redeemable.codes().isEmpty()) {
                throw LoyaltyException.badRequest("REDEEMABLE_AT_NOT_ALLOWED",
                        "A voucher paid at the redeem is paid to its issuer only: leave redeemableAt out.");
            }
        }

        String reference = req.partnerReference().strip();
        // The validity joins the fingerprint only when sent, so an order made
        // before V65 (or without it) keeps the fingerprint it was stored with;
        // likewise the merchants it can be used at (V68). The usage (V66) joins
        // it only when SINGLE, so a MULTIPLE order keeps its stored fingerprint.
        String fingerprint = sha256(String.join("|", channel, nz(outlet), nz(terminalId), amount.toPlainString(),
                currency, phone, payAtRedeem ? AT_REDEEM : method, nz(evidence.approvalCode()),
                nz(evidence.collectionRef()))
                + (validityDays != null ? "|validity=" + validityDays : "")
                + (singleRedemption ? "|usage=SINGLE" : "")
                + redeemable.fingerprintPart());
        return new Sale(reference, channel, outlet, terminalId, blankToNull(req.operatorRef()),
                blankToNull(req.endUserRef()), amount, currency, phone, name, method, evidence.approvalCode(),
                evidence.last4(), evidence.collectedVia(), evidence.collectionRef(),
                payAtRedeem ? null : blankToNull(f.payerPhone()), evidence.key(), fingerprint,
                Boolean.TRUE.equals(req.confirmDuplicate()), validityDays, singleRedemption, redeemable, payAtRedeem);
    }

    /**
     * Whether a payment method fits where the sale happens (partner guide §12):
     * cash and a card swipe are taken at a POS; an InnBucks code is approved in
     * the customer's app, so it is ONLINE or APP; an account debit is never a POS's.
     */
    private static boolean fitsChannel(String method, String channel) {
        boolean pos = channel.equals("POS");
        return switch (method) {
            case "CASH", "CARD_SWIPE" -> pos;
            case "INNBUCKS_CODE", "INNBUCKS_ACCOUNT" -> !pos;
            default -> true;
        };
    }

    /** The payment evidence a method needs, checked: a card's approval code, or a collection's reference. */
    record Evidence(String approvalCode, String last4, String collectedVia, String collectionRef, String key) {
        static final Evidence NONE = new Evidence(null, null, null, null, null);
    }

    /**
     * The evidence the partner guide requires per method ("additionalData"):
     * CARD_SWIPE an approval code (4 to 12 letters or digits) and optionally the
     * card's last four digits; MERCHANT_COLLECTED how and under which reference
     * the money was collected. Each piece pays one sale only ({@link Evidence#key}).
     */
    private static Evidence evidence(String method, PartnerVoucherDtos.Funding f, String terminalId, String outlet) {
        switch (method) {
            case "CARD_SWIPE" -> {
                String approvalCode = f.approvalCode() == null ? "" : f.approvalCode().strip().toUpperCase(Locale.ROOT);
                if (!APPROVAL_CODE.matcher(approvalCode).matches()) {
                    throw LoyaltyException.badRequest("APPROVAL_CODE_REQUIRED",
                            "Enter the approval code printed on the card machine slip (4 to 12 letters or digits).");
                }
                String last4 = blankToNull(f.last4());
                if (last4 != null && !LAST4.matcher(last4).matches()) {
                    throw LoyaltyException.badRequest("INVALID_CARD_LAST4",
                            "last4 must be exactly the last four digits of the card, or left out.");
                }
                // An approval code is unique per terminal per day, not for ever.
                String key = "CARD:" + (terminalId != null ? terminalId : outlet != null ? outlet : "-") + ":"
                        + LocalDate.now(ZoneOffset.UTC) + ":" + approvalCode;
                return new Evidence(approvalCode, last4, null, null, key);
            }
            case "MERCHANT_COLLECTED" -> {
                String collectedVia = f.collectedVia() == null ? "" : f.collectedVia().strip().toUpperCase(Locale.ROOT);
                String collectionRef = blankToNull(f.collectionRef());
                if (!COLLECTED_VIA.contains(collectedVia) || collectionRef == null) {
                    // A business refusal (partner guide §10, 006 "missing required
                    // additionalData keys"), not a malformed request.
                    throw LoyaltyException.badRequest("COLLECTION_DETAILS_REQUIRED",
                            "Send collectedVia (" + String.join(", ", COLLECTED_VIA.stream().sorted().toList())
                                    + ") and collectionRef for money the merchant collected.");
                }
                return new Evidence(null, null, collectedVia, collectionRef, "COLL:" + collectionRef);
            }
            default -> {
                return Evidence.NONE;
            }
        }
    }

    private static boolean merchantAccepts(VoucherPartnerMerchant link, String method) {
        if (link.getFundingMethods() == null || link.getFundingMethods().isBlank()) {
            return true;
        }
        for (String m : link.getFundingMethods().split(",")) {
            if (m.strip().equalsIgnoreCase(method)) {
                return true;
            }
        }
        return false;
    }

    /** The duplicate-sale check and the per-phone daily limits (rolling 24 hours). */
    private void requireWithinLimits(Linked linked, Sale sale) {
        UUID partnerId = linked.link().getPartnerId();
        UUID none = new UUID(0, 0);
        Instant now = clock.instant();
        if (!sale.confirmDuplicate()) {
            Instant since = now.minus(props.duplicateWindow());
            long same = readTx.execute(s -> sale.terminalId() != null
                    ? orders.countRecentSameSaleAtTerminal(partnerId, sale.channel(), sale.terminalId(), sale.phone(),
                            sale.amount(), since, none)
                    : orders.countRecentSameSaleWithoutTerminal(partnerId, sale.channel(), sale.phone(),
                            sale.amount(), since, none));
            if (same > 0) {
                throw LoyaltyException.conflict("POSSIBLE_DUPLICATE",
                        "The same sale was made a moment ago. If this is a second sale, send it again with "
                                + "confirmDuplicate: true.");
            }
        }
        Instant dayAgo = now.minus(Duration.ofDays(1));
        int maxCount = linked.link().getPerPhoneDailyCount() != null
                ? linked.link().getPerPhoneDailyCount() : props.perPhoneDailyCount();
        BigDecimal maxValue = linked.link().getPerPhoneDailyValue() != null
                ? linked.link().getPerPhoneDailyValue() : props.perPhoneDailyValue();
        long count = readTx.execute(s -> orders.countForPhoneSince(partnerId, sale.phone(), dayAgo, none));
        if (count >= maxCount) {
            throw limit("PER_PHONE_DAILY_COUNT", "This phone has received the most vouchers allowed in a day.");
        }
        BigDecimal value = readTx.execute(s -> orders.sumForPhoneSince(partnerId, sale.phone(), dayAgo, none));
        if (value.add(sale.amount()).compareTo(maxValue) > 0) {
            throw limit("PER_PHONE_DAILY_VALUE", "This phone has received the most voucher value allowed in a day.");
        }
    }

    private VoucherPurchaseOrder newOrder(VoucherPartner partner, Linked linked, Sale sale, List<UUID> redeemableAt) {
        Merchant merchant = linked.merchant();
        VoucherPurchaseOrder o = new VoucherPurchaseOrder();
        o.setOrderRef(newOrderRef());
        o.setTenantId(merchant.getTenantId());
        o.setMerchantId(merchant.getId());
        o.setAmount(sale.amount());
        o.setCurrency(sale.currency());
        o.setPayerPhone(sale.payerPhone() != null ? sale.payerPhone() : sale.phone());
        o.setVoucherType(Voucher.VoucherType.SINGLE_USE);
        o.setUsageLimit(1);
        o.setAssigneePhone(sale.phone());
        o.setAssigneeName(sale.name());
        // Issuer: the partner, not a person. The till-only columns stay empty.
        o.setIssuerUserId(partner.getId());
        o.setIssuerEmail(trim("partner:" + partner.getName(), 200));
        o.setCashConfirmedBy(trim("partner:" + partner.getName()
                + (sale.operatorRef() != null ? "/" + sale.operatorRef() : ""), 200));
        o.setExpiresAt(clock.instant().plus(orderTtl));
        o.setPartnerId(partner.getId());
        o.setPartnerReference(sale.reference());
        o.setRequestFingerprint(sale.fingerprint());
        o.setChannel(sale.channel());
        o.setOutlet(sale.outlet());
        o.setTerminalId(sale.terminalId());
        o.setOperatorRef(sale.operatorRef());
        o.setEndUserRef(sale.endUserRef());
        o.setValidityDays(sale.validityDays());
        o.setSingleRedemption(sale.singleRedemption());
        o.setRedeemableMerchantIds(redeemableAt.isEmpty() ? null : redeemableAt);
        o.setFundingMethod(sale.method());
        o.setFundingStatus(FundingStatus.NOT_STARTED);
        o.setFundingEvidenceKey(sale.evidenceKey());
        if ("CARD_SWIPE".equals(sale.method())) {
            o.setCardApprovalCode(sale.approvalCode());
            o.setCardLast4(sale.last4());
        }
        if (sale.payAtRedeem()) {
            // Flow 2 (V69): the code is reserved now and the voucher takes it once
            // an agent's payment confirms. The order lives as long as the code;
            // the voucher it becomes sends no issue message (the customer was
            // told how to pay when the code was generated).
            o.setPayAtRedeem(true);
            o.setReservedCode(voucherService.reserveCode());
            o.setExpiresAt(voucherService.partnerExpiry(merchant.getTenantId(), merchant, sale.validityDays()));
            o.setDeliveryChannel(Voucher.DeliveryChannel.NONE);
        }
        return o;
    }

    private FundingLeg legOf(VoucherPurchaseOrder o) {
        return new FundingLeg(o.getId(), o.getPartnerReference(), o.getMerchantId(), o.getFundingMethod(),
                o.getAmount(), o.getCurrency(), o.getAssigneePhone(), null, o.getFundingStartedAt());
    }

    private VoucherPurchaseOrder load(UUID orderId) {
        return readTx.execute(s -> orders.findById(orderId).orElseThrow());
    }

    /**
     * The order as it now stands. A POS sale carries the voucher code so the
     * till can print it on the customer's slip (owner decision, 2026-10-26),
     * here and on every later read of the same sale (a replay, an inquiry by
     * reference), so a slip that failed to print can be printed again. An
     * ONLINE sale never carries it: the code reaches that customer only on
     * their phone. The code is a bearer credential: never log this result.
     */
    PartnerVoucherDtos.GenerateResult toResult(VoucherPurchaseOrder o, String merchantCode, boolean replayed) {
        if (o.isPayAtRedeem()) {
            return payAtRedeemResult(o, merchantCode, replayed);
        }
        String status = statusOf(o);
        PartnerVoucherDtos.PaymentView payment = o.getFundingStatus() == FundingStatus.AWAITING
                && o.getPaymentCode() != null
                ? new PartnerVoucherDtos.PaymentView(o.getFundingMethod(), o.getPaymentCode(), null,
                        o.getPaymentExpiresAt())
                : null;
        boolean printed = "POS".equals(o.getChannel());
        PartnerVoucherDtos.VoucherView voucher = o.getVoucherId() == null ? null
                : readTx.execute(s -> vouchers.findById(o.getVoucherId()).map(v ->
                        new PartnerVoucherDtos.VoucherView(v.getId(), v.getStatus().name(), money2(v.getValue()),
                                money2(v.getRemainingBalance()), v.getCurrency(), v.getIssuedAt(), v.getExpiresAt(),
                                MsisdnMasking.mask(v.getAssigneePhone()), printed ? v.getCode() : null,
                                usageOf(v), redeemableAtView(v), false))
                        .orElse(null));
        String fundingStatus = o.getFundingStatus() == null ? null : switch (o.getFundingStatus()) {
            case NOT_STARTED, AWAITING -> "PENDING";
            default -> o.getFundingStatus().name();
        };
        return new PartnerVoucherDtos.GenerateResult(merchantCode, o.getPartnerReference(), o.getOrderRef(), status,
                o.getChannel(), o.getOutlet(), o.getTerminalId(), money2(o.getAmount()), o.getCurrency(),
                new PartnerVoucherDtos.FundingView(o.getFundingMethod(), fundingStatus, o.getFundingFailureCode(),
                        provider.simulated()),
                payment, voucher, replayed);
    }

    private String statusOf(VoucherPurchaseOrder o) {
        if (o.getVoucherId() != null) {
            return "ISSUED";
        }
        if (o.getFundingStatus() == null) {
            return "PROCESSING";
        }
        return switch (o.getFundingStatus()) {
            case FAILED -> "FAILED";
            case AWAITING -> "AWAITING_PAYMENT";
            case NOT_STARTED -> o.getExpiresAt() != null && o.getExpiresAt().isBefore(clock.instant())
                    ? "EXPIRED" : "PROCESSING";
            default -> "PROCESSING";
        };
    }

    private PartnerVoucherDtos.VoucherBalanceView balanceOf(Voucher v) {
        boolean live = Voucher.LIVE_STATUSES.contains(v.getStatus())
                && (v.getExpiresAt() == null || v.getExpiresAt().isAfter(clock.instant()))
                && v.getRemainingBalance() != null && v.getRemainingBalance().signum() > 0;
        List<PartnerVoucherDtos.MerchantRef> redeemableAt = readTx.execute(s -> redeemableAtView(v));
        return new PartnerVoucherDtos.VoucherBalanceView(v.getId(), v.getStatus().name(), live, money2(v.getValue()),
                money2(v.getRemainingBalance()), v.getCurrency(), v.getExpiresAt(), usageOf(v), redeemableAt, false);
    }

    // ========================================================= Flow 2 views (V69)

    /**
     * A Flow 2 order as it now stands. The code is always in it: the customer
     * gives it to the agent, and until it is paid it is worth nothing. Status
     * words are the partner guide's (§11): ISSUED (not paid yet), PENDING (an
     * agent's payment is in progress), EXPIRED, and once paid the voucher's own
     * REDEEMED. {@code funding.status} tells the two kinds of ISSUED apart:
     * AT_REDEEM until an agent is paid, then CONFIRMED.
     */
    private PartnerVoucherDtos.GenerateResult payAtRedeemResult(VoucherPurchaseOrder o, String merchantCode,
                                                               boolean replayed) {
        VoucherRedeemPayment last = readTx.execute(s ->
                payments.findFirstByOrderIdOrderByStartedAtDesc(o.getId()).orElse(null));
        PartnerVoucherDtos.VoucherView voucher;
        if (o.getVoucherId() != null) {
            voucher = readTx.execute(s -> vouchers.findById(o.getVoucherId()).map(v ->
                            new PartnerVoucherDtos.VoucherView(v.getId(), v.getStatus().name(), money2(v.getValue()),
                                    money2(v.getRemainingBalance()), v.getCurrency(), v.getIssuedAt(),
                                    v.getExpiresAt(), MsisdnMasking.mask(v.getAssigneePhone()), v.getCode(),
                                    usageOf(v), redeemableAtView(v), false))
                    .orElse(null));
        } else {
            voucher = new PartnerVoucherDtos.VoucherView(null, unpaidStatus(o, last), money2(o.getAmount()),
                    money2(o.getAmount()), o.getCurrency(), o.getCreatedAt(), o.getExpiresAt(),
                    MsisdnMasking.mask(o.getAssigneePhone()), o.getReservedCode(), "SINGLE", issuerOnly(o),
                    true);
        }
        String status = o.getVoucherId() == null && expired(o) ? "EXPIRED" : "ISSUED";
        return new PartnerVoucherDtos.GenerateResult(merchantCode, o.getPartnerReference(), o.getOrderRef(), status,
                o.getChannel(), o.getOutlet(), o.getTerminalId(), money2(o.getAmount()), o.getCurrency(),
                orderFundingView(o, last), null, voucher, replayed);
    }

    /** What an inquiry by code answers for a Flow 2 code that is not paid yet. */
    private PartnerVoucherDtos.VoucherBalanceView unpaidBalance(VoucherPurchaseOrder o) {
        VoucherRedeemPayment last = readTx.execute(s ->
                payments.findFirstByOrderIdOrderByStartedAtDesc(o.getId()).orElse(null));
        String status = unpaidStatus(o, last);
        return new PartnerVoucherDtos.VoucherBalanceView(null, status, "ISSUED".equals(status),
                money2(o.getAmount()), money2(o.getAmount()), o.getCurrency(), o.getExpiresAt(), "SINGLE",
                issuerOnly(o), true);
    }

    private String unpaidStatus(VoucherPurchaseOrder o, VoucherRedeemPayment last) {
        if (last != null && last.getStatus() != VoucherRedeemPayment.Status.FAILED) {
            return "PENDING";
        }
        return expired(o) ? "EXPIRED" : "ISSUED";
    }

    private boolean expired(VoucherPurchaseOrder o) {
        return o.getExpiresAt() != null && !o.getExpiresAt().isAfter(clock.instant());
    }

    private PartnerVoucherDtos.FundingView orderFundingView(VoucherPurchaseOrder o, VoucherRedeemPayment last) {
        if (o.getVoucherId() != null) {
            return new PartnerVoucherDtos.FundingView(o.getFundingMethod(), "CONFIRMED", null, provider.simulated());
        }
        if (last != null && last.getStatus() != VoucherRedeemPayment.Status.FAILED) {
            return new PartnerVoucherDtos.FundingView(last.getFundingMethod(), "PENDING", null, provider.simulated());
        }
        return new PartnerVoucherDtos.FundingView(null, AT_REDEEM, null, provider.simulated());
    }

    /** A Flow 2 code works at its issuer only. */
    private List<PartnerVoucherDtos.MerchantRef> issuerOnly(VoucherPurchaseOrder o) {
        return readTx.execute(s -> {
            String code = o.getPartnerId() == null ? null : links.findByPartnerIdOrderByMerchantCodeAsc(o.getPartnerId())
                    .stream().filter(l -> l.getMerchantId().equals(o.getMerchantId()))
                    .map(VoucherPartnerMerchant::getMerchantCode).findFirst().orElse(null);
            String name = merchants.findById(o.getMerchantId()).map(Merchant::getName).orElse(null);
            return List.of(new PartnerVoucherDtos.MerchantRef(code, name));
        });
    }

    // ============================================================ redeemableAt (V68)

    /**
     * Parses {@code redeemableAt}: "ANY" alone, or codes. Only the shape is checked
     * here, so a replay of the same request matches its stored fingerprint even if
     * a partnership has changed since; which merchants it reaches is
     * {@link #resolveRedeemableAt}'s question, asked for a new order only.
     */
    private static Redeemable redeemable(Linked linked, List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Redeemable.ISSUER_ONLY;
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String code : raw) {
            if (code == null || code.isBlank()) {
                throw validation("redeemableAt", "must not contain a blank code");
            }
            codes.add(code.strip().toUpperCase(Locale.ROOT));
        }
        if (codes.contains(ANY)) {
            if (codes.size() > 1) {
                throw validation("redeemableAt", "ANY cannot be combined with merchant codes");
            }
            return new Redeemable(true, List.of());
        }
        codes.remove(linked.link().getMerchantCode().toUpperCase(Locale.ROOT));
        if (codes.size() > MAX_REDEEMABLE_AT) {
            throw validation("redeemableAt", "can name at most " + MAX_REDEEMABLE_AT + " merchants");
        }
        return new Redeemable(false, List.copyOf(codes));
    }

    /**
     * The merchants a new voucher may also be used at: every partner for "ANY"
     * ({@link #partnerMerchants}), else the codes named, or a 400
     * {@code MERCHANT_NOT_A_PARTNER} naming every code that is not one: a code
     * this partner was not given, a merchant not live for it, or one that is
     * neither in the issuer's own program nor in a live partnership with it (both
     * merchants taking part). The redeeming merchant must be this partner's, as
     * every partner voucher is redeemed through the partner that sold it.
     */
    private List<UUID> resolveRedeemableAt(Linked linked, Redeemable redeemable) {
        if (redeemable.any()) {
            return partnerMerchants(linked);
        }
        List<String> codes = redeemable.codes();
        if (codes.isEmpty()) {
            return List.of();
        }
        return readTx.execute(s -> {
            Map<String, VoucherPartnerMerchant> byCode = new HashMap<>();
            for (VoucherPartnerMerchant l : links.findByPartnerIdOrderByMerchantCodeAsc(linked.link().getPartnerId())) {
                byCode.put(l.getMerchantCode().toUpperCase(Locale.ROOT), l);
            }
            List<String> refused = new ArrayList<>();
            Map<UUID, String> codeOf = new LinkedHashMap<>();
            for (String code : codes) {
                VoucherPartnerMerchant l = byCode.get(code);
                if (l == null || l.getStatus() != VoucherPartner.Status.ACTIVE) {
                    refused.add(code);
                } else {
                    codeOf.put(l.getMerchantId(), code);
                }
            }
            List<Merchant> targets = new ArrayList<>();
            for (Merchant m : merchants.findAllById(codeOf.keySet())) {
                if (m.getStatus() != Merchant.Status.ACTIVE || !m.isEscrowMode()) {
                    refused.add(codeOf.get(m.getId()));
                } else {
                    targets.add(m);
                }
            }
            for (Merchant m : partnerships.refusedTargets(linked.merchant(), targets)) {
                refused.add(codeOf.get(m.getId()));
            }
            if (!refused.isEmpty()) {
                List<String> inOrder = codes.stream().filter(refused::contains).toList();
                throw VoucherPartnershipService.notAPartner(inOrder);
            }
            // In the order sent.
            return codes.stream().map(c -> byCode.get(c).getMerchantId()).toList();
        });
    }

    /**
     * Where an "ANY" voucher can also be used, worked out once, for a new order:
     * every merchant this partner serves (an ACTIVE link) that is live, in escrow,
     * not the issuer, and either in the issuer's own program or in a live
     * partnership with both merchants taking part. In merchant-code order. A
     * merchant another partner serves is left out, because a partner voucher is
     * redeemed through the partner that sold it. Empty when there is none: the
     * voucher then works at the issuer only. A replay never asks again, so a
     * partnership that changed since keeps the stored list.
     */
    private List<UUID> partnerMerchants(Linked linked) {
        UUID issuerId = linked.merchant().getId();
        return readTx.execute(s -> {
            List<UUID> candidates = new ArrayList<>();
            for (VoucherPartnerMerchant l : links.findByPartnerIdOrderByMerchantCodeAsc(linked.link().getPartnerId())) {
                if (l.getStatus() == VoucherPartner.Status.ACTIVE && !l.getMerchantId().equals(issuerId)) {
                    candidates.add(l.getMerchantId());
                }
            }
            if (candidates.isEmpty()) {
                return List.<UUID>of();
            }
            List<Merchant> live = new ArrayList<>();
            for (Merchant m : merchants.findAllById(candidates)) {
                if (m.getStatus() == Merchant.Status.ACTIVE && m.isEscrowMode()) {
                    live.add(m);
                }
            }
            Set<UUID> refused = new HashSet<>();
            for (Merchant m : partnerships.refusedTargets(linked.merchant(), live)) {
                refused.add(m.getId());
            }
            Set<UUID> accepted = new HashSet<>();
            for (Merchant m : live) {
                if (!refused.contains(m.getId())) {
                    accepted.add(m.getId());
                }
            }
            return candidates.stream().filter(accepted::contains).toList();
        });
    }

    /**
     * Every merchant a voucher can be used at, the issuer first, by the codes its
     * partner was given and their names. Call inside a read transaction.
     */
    private List<PartnerVoucherDtos.MerchantRef> redeemableAtView(Voucher v) {
        List<UUID> ids = new ArrayList<>();
        ids.add(v.getMerchantId());
        if (v.getRedeemableMerchantIds() != null) {
            v.getRedeemableMerchantIds().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        }
        Map<UUID, String> codeOf = new HashMap<>();
        if (v.getPartnerId() != null) {
            for (VoucherPartnerMerchant l : links.findByPartnerIdOrderByMerchantCodeAsc(v.getPartnerId())) {
                codeOf.put(l.getMerchantId(), l.getMerchantCode());
            }
        }
        Map<UUID, String> nameOf = new HashMap<>();
        for (Merchant m : merchants.findAllById(ids)) {
            nameOf.put(m.getId(), m.getName());
        }
        return ids.stream().map(id -> new PartnerVoucherDtos.MerchantRef(codeOf.get(id), nameOf.get(id))).toList();
    }

    private static String usageOf(Voucher v) {
        return v.isSingleRedemption() ? "SINGLE" : "MULTIPLE";
    }

    /** This partner's successful use with this reference at this merchant, or null. */
    private VoucherRedemption findRedemption(VoucherPartner partner, UUID merchantId, String reference) {
        return readTx.execute(s -> redemptions.findFirstByMerchantIdAndReferenceAndResult(
                        merchantId, reference, VoucherRedemption.Result.SUCCESS)
                .filter(found -> partner.getId().equals(found.getPartnerId()))
                .orElse(null));
    }

    /** A use's result; a Flow 2 use (V69) carries the agent's payment as {@code funding}. */
    private PartnerVoucherDtos.RedeemResult redeemResultOf(VoucherRedemption r, boolean replayed) {
        PartnerVoucherDtos.FundingView funding = readTx.execute(s -> payments.findByRedemptionId(r.getId())
                .map(this::fundingViewOf).orElse(null));
        return redeemResultOf(r, replayed, funding);
    }

    private PartnerVoucherDtos.RedeemResult redeemResultOf(VoucherRedemption r, boolean replayed,
                                                           PartnerVoucherDtos.FundingView funding) {
        BigDecimal after = r.getBalanceAfter();
        boolean spent = after == null || after.signum() <= 0;
        String currency = readTx.execute(s -> vouchers.findById(r.getVoucherId()).map(Voucher::getCurrency)
                .orElse(null));
        return new PartnerVoucherDtos.RedeemResult(r.getId(), r.getVoucherId(),
                (spent ? Voucher.Status.REDEEMED : Voucher.Status.PARTIALLY_USED).name(), money2(r.getAmount()),
                money2(after), currency, r.getRedeemedAt(), r.getReference(), replayed,
                new PartnerVoucherDtos.SettlementView(
                        r.getSettlementStatus() == null ? null : r.getSettlementStatus().name(),
                        r.getSettlementRef(), r.getSettledAt()),
                funding);
    }

    private VoucherService.PartnerRedeemScope scope(Linked linked, String endUserRef, String endUserPhone,
                                                    String channel, String terminalId, String currency) {
        boolean bind = linked.link().isBindRedeemToHolder();
        if (bind && (endUserPhone == null || endUserPhone.isBlank())) {
            throw validation("endUserPhone", "is required: the verified phone on the customer's account");
        }
        return new VoucherService.PartnerRedeemScope(linked.link().getPartnerId(), endUserRef,
                blankToNull(endUserPhone), channel, terminalId, bind, currency);
    }

    /** The lockout identity: one partner's one merchant's one customer account. */
    private static VoucherGuessGuard.Identity guardIdentity(VoucherPartner partner, UUID merchantId,
                                                            String endUserRef) {
        return new VoucherGuessGuard.Identity("partner", partner.getId() + ":" + merchantId + ":" + endUserRef);
    }

    private static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = HtmlSanitizer.stripAll(raw);
        if (s == null) {
            return null;
        }
        // Control, format (bidi, zero-width) and line/paragraph separators become spaces.
        s = s.replaceAll("[\\p{Cc}\\p{Cf}\\u2028\\u2029]", " ").replaceAll("\\s+", " ").strip();
        if (s.isEmpty()) {
            return null;
        }
        if (LINK_LIKE.matcher(s).find()) {
            throw LoyaltyException.badRequest("BAD_NAME", "The customer's name can't contain a link.");
        }
        return s.length() > 40 ? s.substring(0, 40).strip() : s;
    }

    private static LoyaltyDataException validation(String field, String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(field, message);
        return new LoyaltyDataException(HttpStatus.BAD_REQUEST, "400 BAD_REQUEST", "Validation failed", fields);
    }

    private static LoyaltyDataException limit(String which, String message) {
        return new LoyaltyDataException(HttpStatus.UNPROCESSABLE_ENTITY, "LIMIT_EXCEEDED", message,
                Map.of("limit", which));
    }

    private static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw validation(field, "is required");
        }
        return value.strip();
    }

    private static BigDecimal money2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** VCH-<12 uppercase hex>, the purchase-order reference shape. */
    private static String newOrderRef() {
        StringBuilder sb = new StringBuilder("VCH-");
        for (int i = 0; i < 12; i++) {
            sb.append(HEX[RANDOM.nextInt(16)]);
        }
        return sb.toString();
    }
}
