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
import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.exception.LoyaltyDataException;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherPartnerMerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherPurchaseOrderRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.PartnerRequest;
import com.innbucks.loyaltyservice.service.ExchangeRateService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.VoucherGuessGuard;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.partner.VoucherFundingProvider.FundingLeg;
import com.innbucks.loyaltyservice.service.partner.VoucherFundingProvider.FundingOutcome;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
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

    private static final Pattern APPROVAL_CODE = Pattern.compile("[A-Z0-9]{4,12}");
    private static final Pattern LAST4 = Pattern.compile("[0-9]{4}");
    private static final Pattern LINK_LIKE = Pattern.compile(
            "(?i)(https?:|www\\.|\\b[a-z0-9-]+\\.(com|net|org|info|biz|io|co|zw|me|ly|link|xyz)\\b)");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final VoucherPartnerMerchantRepository links;
    private final MerchantRepository merchants;
    private final VoucherPurchaseOrderRepository orders;
    private final VoucherRepository vouchers;
    private final VoucherRedemptionRepository redemptions;
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
                                 PlatformTransactionManager txManager, LoyaltyProperties loyaltyProps) {
        this.links = links;
        this.merchants = merchants;
        this.orders = orders;
        this.vouchers = vouchers;
        this.redemptions = redemptions;
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

        if (!voucherService.wouldExpire(linked.merchant().getTenantId(), linked.merchant())) {
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

        requireWithinLimits(linked, sale);

        UUID orderId;
        try {
            orderId = tx.execute(s -> orders.saveAndFlush(newOrder(partner, linked, sale)).getId());
        } catch (DataIntegrityViolationException clash) {
            String detail = String.valueOf(clash.getMostSpecificCause().getMessage());
            if (detail.contains("uq_vpo_partner_reference")) {
                // A concurrent request with the same reference won the claim.
                VoucherPurchaseOrder winner = readTx.execute(s -> orders.findByMerchantIdAndPartnerReference(
                        linked.merchant().getId(), sale.reference()).orElseThrow());
                return replay(winner, sale, linked);
            }
            if (detail.contains("uq_vpo_funding_evidence")) {
                throw "CARD_SWIPE".equals(sale.method())
                        ? LoyaltyException.conflict("APPROVAL_CODE_ALREADY_USED",
                                "This card approval code already paid for another voucher.")
                        : LoyaltyException.conflict("COLLECTION_REF_ALREADY_USED",
                                "This collection reference already paid for another voucher.");
            }
            throw clash;
        }
        drive(orderId);
        return generateAnswer(load(orderId), linked, false);
    }

    private Answer<PartnerVoucherDtos.GenerateResult> replay(VoucherPurchaseOrder existing, Sale sale, Linked linked) {
        if (!sale.fingerprint().equals(existing.getRequestFingerprint())) {
            throw new LoyaltyException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_MISMATCH",
                    "This partnerReference was already used for a different request.");
        }
        if (existing.getFundingStatus() == FundingStatus.NOT_STARTED) {
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
            if (o.getFundingStatus() != FundingStatus.NOT_STARTED) {
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
                if (o.getFundingStatus() != FundingStatus.CONFIRMED || o.getVoucherId() != null) {
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
                resolveOpen(o.getId());
                yield new Answer<>(HttpStatus.OK, "200 OK", "Order found",
                        toResult(load(o.getId()), linked.link().getMerchantCode(), false));
            }
            case "CODE" -> {
                String code = required("code", req.code());
                String endUserRef = required("endUserRef", req.endUserRef());
                VoucherService.PartnerRedeemScope scope = scope(linked, endUserRef, req.endUserPhone(),
                        "ONLINE", req.terminalId(), null);
                Voucher v = guard.attemptAs(guardIdentity(partner, merchantId, endUserRef),
                        () -> voucherService.inquireForPartner(linked.merchant().getTenantId(), merchantId, code, scope));
                yield new Answer<>(HttpStatus.OK, "200 OK", "Voucher found", balanceOf(v));
            }
            case "REDEMPTION" -> {
                String ref = required("reference", req.reference());
                VoucherRedemption r = findRedemption(partner, merchantId, ref);
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
     */
    public PartnerVoucherDtos.RedeemResult redeem(PartnerRequest caller, PartnerVoucherDtos.RedeemRequest req) {
        VoucherPartner partner = caller.partner();
        Linked linked = resolve(partner, req.merchant());
        UUID merchantId = linked.merchant().getId();
        String channel = req.channel() == null || req.channel().isBlank()
                ? "ONLINE" : req.channel().strip().toUpperCase(Locale.ROOT);
        if (!channel.equals("ONLINE") && !channel.equals("POS")) {
            throw validation("channel", "must be POS or ONLINE");
        }
        String currency = req.currency() == null || req.currency().isBlank()
                ? null : req.currency().strip().toUpperCase(Locale.ROOT);
        VoucherService.PartnerRedeemScope scope = scope(linked, req.endUserRef().strip(), req.endUserPhone(),
                channel, blankToNull(req.terminalId()), currency);
        Dtos.RedeemVoucherRequest redeem = new Dtos.RedeemVoucherRequest(merchantId, req.code(), null, null, null,
                null, req.amount(), req.reference());
        Dtos.RedemptionResponse used = guard.attemptAs(guardIdentity(partner, merchantId, scope.endUserRef()),
                () -> voucherService.redeemForPartnerIdempotent(linked.merchant().getTenantId(), merchantId,
                        redeem, scope));
        trySettle(used.redemptionId());
        VoucherRedemption r = readTx.execute(s -> redemptions.findById(used.redemptionId()).orElseThrow());
        metrics.incPartnerRequest("redeem", used.replayed() ? "replayed" : "redeemed");
        return redeemResultOf(r, used.replayed());
    }

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
                String evidenceKey, String fingerprint, boolean confirmDuplicate) {}

    private Sale validate(Linked linked, PartnerVoucherDtos.GenerateRequest req) {
        String channel = req.channel().strip().toUpperCase(Locale.ROOT);
        if (!channel.equals("POS") && !channel.equals("ONLINE")) {
            throw validation("channel", "must be POS or ONLINE");
        }
        PartnerVoucherDtos.Funding f = req.funding();
        String method = f.method().strip().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw validation("funding.method", "must be one of " + String.join(", ", METHODS.stream().sorted().toList()));
        }
        boolean allowedHere = AVAILABLE_METHODS.contains(method)
                && !(channel.equals("ONLINE") && (method.equals("CASH") || method.equals("CARD_SWIPE")))
                && !(channel.equals("POS") && method.equals("INNBUCKS_ACCOUNT"))
                && merchantAccepts(linked.link(), method);
        if (!allowedHere) {
            throw LoyaltyException.badRequest("FUNDING_METHOD_NOT_ALLOWED",
                    "This payment method is not available here.");
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

        String approvalCode = null;
        String last4 = null;
        String collectedVia = null;
        String collectionRef = null;
        String evidenceKey = null;
        switch (method) {
            case "CARD_SWIPE" -> {
                approvalCode = f.approvalCode() == null ? "" : f.approvalCode().strip().toUpperCase(Locale.ROOT);
                if (!APPROVAL_CODE.matcher(approvalCode).matches()) {
                    throw LoyaltyException.badRequest("APPROVAL_CODE_REQUIRED",
                            "Enter the approval code printed on the card machine slip (4 to 12 letters or digits).");
                }
                last4 = blankToNull(f.last4());
                if (last4 != null && !LAST4.matcher(last4).matches()) {
                    throw LoyaltyException.badRequest("INVALID_CARD_LAST4",
                            "last4 must be exactly the last four digits of the card, or left out.");
                }
                // An approval code is unique per terminal per day, not for ever.
                evidenceKey = "CARD:" + (terminalId != null ? terminalId : outlet != null ? outlet : "-") + ":"
                        + LocalDate.now(ZoneOffset.UTC) + ":" + approvalCode;
            }
            case "MERCHANT_COLLECTED" -> {
                collectedVia = f.collectedVia() == null ? "" : f.collectedVia().strip().toUpperCase(Locale.ROOT);
                if (!COLLECTED_VIA.contains(collectedVia)) {
                    throw validation("funding.collectedVia",
                            "must be one of " + String.join(", ", COLLECTED_VIA.stream().sorted().toList()));
                }
                collectionRef = blankToNull(f.collectionRef());
                if (collectionRef == null) {
                    throw validation("funding.collectionRef", "is required");
                }
                evidenceKey = "COLL:" + collectionRef;
            }
            default -> { }
        }

        String reference = req.partnerReference().strip();
        String fingerprint = sha256(String.join("|", channel, nz(outlet), nz(terminalId), amount.toPlainString(),
                currency, phone, method, nz(approvalCode), nz(collectionRef)));
        return new Sale(reference, channel, outlet, terminalId, blankToNull(req.operatorRef()),
                blankToNull(req.endUserRef()), amount, currency, phone, name, method, approvalCode, last4,
                collectedVia, collectionRef, blankToNull(f.payerPhone()), evidenceKey, fingerprint,
                Boolean.TRUE.equals(req.confirmDuplicate()));
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

    private VoucherPurchaseOrder newOrder(VoucherPartner partner, Linked linked, Sale sale) {
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
        o.setFundingMethod(sale.method());
        o.setFundingStatus(FundingStatus.NOT_STARTED);
        o.setFundingEvidenceKey(sale.evidenceKey());
        if ("CARD_SWIPE".equals(sale.method())) {
            o.setCardApprovalCode(sale.approvalCode());
            o.setCardLast4(sale.last4());
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
                                MsisdnMasking.mask(v.getAssigneePhone()), printed ? v.getCode() : null))
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
        return new PartnerVoucherDtos.VoucherBalanceView(v.getId(), v.getStatus().name(), live, money2(v.getValue()),
                money2(v.getRemainingBalance()), v.getCurrency(), v.getExpiresAt());
    }

    private VoucherRedemption findRedemption(VoucherPartner partner, UUID merchantId, String reference) {
        VoucherRedemption r = readTx.execute(s -> redemptions.findFirstByMerchantIdAndReferenceAndResult(
                        merchantId, reference, VoucherRedemption.Result.SUCCESS)
                .filter(found -> partner.getId().equals(found.getPartnerId()))
                .orElse(null));
        if (r == null) {
            throw LoyaltyException.notFound("redemption");
        }
        return r;
    }

    private PartnerVoucherDtos.RedeemResult redeemResultOf(VoucherRedemption r, boolean replayed) {
        BigDecimal after = r.getBalanceAfter();
        boolean spent = after == null || after.signum() <= 0;
        String currency = readTx.execute(s -> vouchers.findById(r.getVoucherId()).map(Voucher::getCurrency)
                .orElse(null));
        return new PartnerVoucherDtos.RedeemResult(r.getId(), r.getVoucherId(),
                (spent ? Voucher.Status.REDEEMED : Voucher.Status.PARTIALLY_USED).name(), money2(r.getAmount()),
                money2(after), currency, r.getRedeemedAt(), r.getReference(), replayed,
                new PartnerVoucherDtos.SettlementView(
                        r.getSettlementStatus() == null ? null : r.getSettlementStatus().name(),
                        r.getSettlementRef(), r.getSettledAt()));
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
