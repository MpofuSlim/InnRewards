package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherBatch;
import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherBatchRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.CryptoSigner;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@Slf4j
public class VoucherService {

    private final VoucherRepository vouchers;
    private final VoucherBatchRepository batches;
    private final VoucherRedemptionRepository redemptions;
    private final MerchantService merchants;
    private final com.innbucks.loyaltyservice.security.MerchantAuthz merchantAuthz;
    private final com.innbucks.loyaltyservice.config.SupportedCurrencies supportedCurrencies;
    private final LoyaltyRuleRepository rules;
    private final LoyaltyUserRepository users;
    private final UserService userService;
    private final NotificationGateway notifications;
    private final FraudService fraud;
    private final com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics;
    private final com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier;
    private final CryptoSigner signer;
    private final ExchangeRateService fx;
    /** Platform fallback for voucher expiry when no rule sets one (V45). */
    private final int defaultValidityDays;

    /**
     * Carries REFUSED redemptions out to be recorded after this service's
     * transaction rolls back — see {@code VoucherRedemptionRejectedEvent} for why
     * an in-transaction insert (or a REQUIRES_NEW one) cannot do it.
     */
    private final org.springframework.context.ApplicationEventPublisher events;

    public VoucherService(VoucherRepository vouchers,
                          VoucherBatchRepository batches,
                          VoucherRedemptionRepository redemptions,
                          MerchantService merchants,
                          com.innbucks.loyaltyservice.security.MerchantAuthz merchantAuthz,
                          com.innbucks.loyaltyservice.config.SupportedCurrencies supportedCurrencies,
                          LoyaltyRuleRepository rules,
                          LoyaltyUserRepository users,
                          UserService userService,
                          NotificationGateway notifications,
                          FraudService fraud,
                          com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics,
                          com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier,
                          LoyaltyProperties props,
                          ExchangeRateService fx,
                          org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.vouchers = vouchers;
        this.batches = batches;
        this.redemptions = redemptions;
        this.merchants = merchants;
        this.merchantAuthz = merchantAuthz;
        this.supportedCurrencies = supportedCurrencies;
        this.rules = rules;
        this.users = users;
        this.userService = userService;
        this.notifications = notifications;
        this.fraud = fraud;
        this.metrics = metrics;
        this.memberNotifier = memberNotifier;
        this.signer = new CryptoSigner(props.voucher().secret());
        this.fx = fx;
        this.defaultValidityDays = props.voucher().defaultValidityDays();
    }

    public Dtos.VoucherResponse issue(UUID tenantId, Dtos.IssueVoucherRequest req) {
        // Object-level authz replaces the retired template's tenant check, and
        // is STRICTER than what it replaces: the template was only ever
        // tenant-scoped, so a MERCHANT_ADMIN could issue from a sibling
        // merchant's template. Now the caller must administer the issuing
        // merchant (SUPER_ADMIN bypasses; SHOP_ADMIN is pinned by the JWT
        // claim CallerDetails already merged over the body value).
        Merchant merchant = merchantAuthz.requireCallerAdministersMerchant(
                tenantId, CallerDetails.resolveMerchantId(req.merchantId()));
        int usageLimit = resolveUsageLimit(req.voucherType(), req.usageLimit());
        // The sender phone defaults to the issuing caller's own JWT phone —
        // a customer gifting from the app gets their confirmation copy without
        // restating their number. Single-issue only: bulk stock deliberately
        // has no sender, so the default must not apply there.
        String senderPhone = req.senderPhone() != null && !req.senderPhone().isBlank()
                ? req.senderPhone() : CallerDetails.currentPhoneNumber();
        Voucher v = createVoucher(tenantId, merchant, null,
                req.assignedUserId(), req.assigneePhone(), req.assigneeName(),
                req.senderName(), senderPhone,
                req.deliveryChannel(), req.campaignSource(), req.value(), req.currency(),
                voucherTypeOrDefault(req.voucherType()), usageLimit);
        return finishIssue(v);
    }

    /**
     * Issue the voucher a PAID purchase order describes (V47). The caller —
     * payment-service's S2S confirm, or the staff cash confirmation — has
     * already established that the money is in; authz was enforced when the
     * order was CREATED, so there is no caller check here (the S2S path has
     * no caller at all). Fees, FX freeze, expiry-from-rules and both
     * notifications ride the ordinary issue path unchanged.
     */
    public Dtos.VoucherResponse issueFromOrder(
            com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder order) {
        Merchant merchant = merchants.requireMerchant(order.getTenantId(), order.getMerchantId());
        Voucher v = createVoucher(order.getTenantId(), merchant, null,
                order.getAssignedUserId(), order.getAssigneePhone(), order.getAssigneeName(),
                order.getSenderName(), order.getSenderPhone(),
                order.getDeliveryChannel(), order.getCampaignSource(),
                order.getAmount(), order.getCurrency(),
                order.getVoucherType(), order.getUsageLimit());
        // The confirm has no useful caller JWT (S2S, or a cashier who is not
        // the issuer) — replace the CallerDetails stamps with the identity
        // snapshotted when the order was created.
        v.setShopId(order.getShopId());
        v.setIssuerUserId(order.getIssuerUserId());
        v.setIssuerPhone(order.getIssuerPhone());
        v.setIssuerEmail(order.getIssuerEmail());
        return finishIssue(v);
    }

    /** The shared issue tail: persist, stamp the dispatch attempt, both
     *  notifications (recipient delivery + V46 sender copy), metrics. */
    private Dtos.VoucherResponse finishIssue(Voucher v) {
        vouchers.save(v);
        // Stamp WHEN dispatch was attempted, on THIS thread (before the async
        // hand-off reads the entity on the executor thread). The status is NOT
        // touched: a freshly issued voucher is ISSUED and stays ISSUED until
        // someone opens or spends it (V48 — see Voucher.Status). The send runs
        // off the request thread so a slow gateway never blocks issuance, which
        // is exactly why a status flip here could never have meant "received".
        if (v.getDeliveryChannel() != null && v.getDeliveryChannel() != Voucher.DeliveryChannel.NONE) {
            v.setDeliveredAt(Instant.now());
        }
        String recipientPhone = holderPhone(v);
        notifications.deliver(v, recipientPhone);
        // Sender's confirmation copy (V46): "we should both get the WhatsApp
        // messages". Issue-path ONLY — the transfer path rotates the code and
        // deliberately redacts it from the sender, so a sender copy there would
        // defeat the rotation. Skipped when sender and recipient are the same
        // phone (self-issue): one message, not two identical ones.
        String stampedSenderPhone = v.getSenderPhone();
        if (stampedSenderPhone != null && !stampedSenderPhone.equals(recipientPhone)) {
            notifications.deliverSenderCopy(v, stampedSenderPhone);
        }
        metrics.incVouchersIssued();
        return toResponse(v);
    }

    public List<Dtos.VoucherResponse> issueBulk(UUID tenantId, Dtos.BulkIssueRequest req) {
        Merchant merchant = merchantAuthz.requireCallerAdministersMerchant(
                tenantId, CallerDetails.resolveMerchantId(req.merchantId()));
        int usageLimit = resolveUsageLimit(req.voucherType(), req.usageLimit());
        Voucher.VoucherType type = voucherTypeOrDefault(req.voucherType());
        VoucherBatch batch = new VoucherBatch();
        batch.setTenantId(tenantId);
        batch.setQuantity(req.quantity());
        batch.setCampaign(req.campaign());
        batches.save(batch);

        List<Dtos.VoucherResponse> result = new ArrayList<>(req.quantity());
        for (int i = 0; i < req.quantity(); i++) {
            // No sender identity on bulk: it is unassigned campaign stock, and a
            // per-voucher sender confirmation would message one phone `quantity`
            // times over.
            Voucher v = createVoucher(tenantId, merchant, batch.getId(),
                    null, null, null, null, null, req.deliveryChannel(),
                    req.campaign(), req.value(), req.currency(), type, usageLimit);
            vouchers.save(v);
            result.add(toResponse(v));
        }
        metrics.incVouchersIssued(req.quantity());
        return result;
    }

    /** Absent means SINGLE_USE — the overwhelmingly common case.
     *  Package-visible: VoucherPurchaseService applies the same default. */
    static Voucher.VoucherType voucherTypeOrDefault(Voucher.VoucherType type) {
        return type == null ? Voucher.VoucherType.SINGLE_USE : type;
    }

    /**
     * SINGLE_USE is exactly one use; MULTI_USE takes an explicit limit of two
     * or more. A conflicting pair (SINGLE_USE with a limit above 1, MULTI_USE
     * with 1 or without a limit) is refused rather than silently corrected —
     * the caller plainly meant something this request does not say.
     * Package-visible: VoucherPurchaseService runs the same contract at order
     * creation so a purchase never snapshots a request issue would refuse.
     */
    static int resolveUsageLimit(Voucher.VoucherType type, Integer usageLimit) {
        if (voucherTypeOrDefault(type) == Voucher.VoucherType.SINGLE_USE) {
            if (usageLimit != null && usageLimit != 1) {
                throw LoyaltyException.badRequest("USAGE_LIMIT_CONFLICT",
                        "A SINGLE_USE voucher has exactly one use — omit usageLimit or send 1, "
                                + "or issue it as MULTI_USE.");
            }
            return 1;
        }
        if (usageLimit == null || usageLimit < 2) {
            throw LoyaltyException.badRequest("USAGE_LIMIT_REQUIRED",
                    "A MULTI_USE voucher needs usageLimit of 2 or more.");
        }
        return usageLimit;
    }

    /**
     * WHOSE voucher this is, as a phone number: the explicit assignee phone,
     * else the assigned LoyaltyUser's phone. Null when the voucher has no holder
     * at all (bulk / unassigned stock) — the delivery gateway then just logs and
     * skips. Best-effort resolution; never throws.
     *
     * <p><b>This is the ONE definition of the holder, and it has to be</b>, which
     * is why it is no longer named for delivery. It used to be consulted only
     * when choosing where to send the code, while the two ownership checks
     * ({@code doRedeem}'s assignee branch and {@link #requireCallerMayViewVoucher})
     * compared the caller against the raw {@code assigneePhone} column — and the
     * two disagree about what counts as "no phone". This method treats a BLANK
     * phone as absent and falls back to the assigned user's number; a bare column
     * comparison does not. So a voucher issued with an explicitly blank
     * {@code assigneePhone} alongside an {@code assignedUserId} was DELIVERED to
     * that user and then refused to that same user, the check comparing their
     * live phone claim against {@code ""}.
     *
     * <p>{@code createVoucher} now normalises blank to absent, so no new row can
     * take that shape; this resolution is what covers rows already written that
     * way, and any legacy row with a genuinely null phone. Resolve the holder the
     * same way everywhere, or the person who receives a voucher is not the person
     * allowed to spend it.
     */
    private String holderPhone(Voucher v) {
        if (v.getAssigneePhone() != null && !v.getAssigneePhone().isBlank()) {
            return v.getAssigneePhone();
        }
        if (v.getAssignedUserId() != null) {
            return users.findById(v.getAssignedUserId())
                    .map(LoyaltyUser::getPhoneNumber)
                    .orElse(null);
        }
        return null;
    }

    /**
     * The loyalty account that HOLDS this voucher, resolved from the voucher
     * itself — never from a request field.
     *
     * <p><b>Why this exists.</b> The BLOCKED / registration gates on redeem used
     * to run against {@code RedeemVoucherRequest.userId}: a nullable, unvalidated
     * body field that was never compared to the voucher's own holder. That made
     * both gates opt-in for the caller — a till posting only the code performed
     * no account checks at all, so a blocked holder's voucher and an unregistered
     * holder's voucher both redeemed cleanly — and satisfiable by naming any
     * unrelated ACTIVE account. The voucher already knows whose it is.
     *
     * <p>Empty when the voucher has no holder (bulk stock), which is a real
     * answer: there is no account to check, and campaign stock redeemed at a till
     * is exactly the case that must keep working. Empty too when the holder's
     * phone has no projection in this tenant — the voucher is still valid, and
     * inventing a refusal from a missing row would strand it.
     */
    private java.util.Optional<LoyaltyUser> holderAccount(Voucher v) {
        // PRECEDENCE MUST MATCH holderPhone's, exactly. The first draft of this
        // method preferred assignedUserId while holderPhone prefers the assignee
        // phone, and on a voucher carrying BOTH — which the issue API allows,
        // without cross-validating that they name the same person — the
        // ownership check then admitted the phone's owner while this gate
        // inspected the id's owner. That re-opens, for that shape, the very gate
        // this exists to close: the admitted holder's own account is never
        // consulted. Whatever order is chosen, one order.
        if (v.getAssigneePhone() != null && !v.getAssigneePhone().isBlank()) {
            return users.findByTenantIdAndPhoneNumber(v.getTenantId(), v.getAssigneePhone());
        }
        if (v.getAssignedUserId() != null) {
            // Tenant-checked: a voucher must never reach across tenants for the
            // account whose status decides whether it may be spent. Belt and
            // braces — createVoucher already refuses a cross-tenant
            // assignedUserId — but this is the gate, so it re-checks.
            return users.findById(v.getAssignedUserId())
                    .filter(u -> u.getTenantId().equals(v.getTenantId()));
        }
        return java.util.Optional.empty();
    }

    private Voucher createVoucher(UUID tenantId, Merchant merchant, UUID batchId,
                                  UUID assignedUserId, String assigneePhone, String assigneeName,
                                  String senderName, String senderPhone,
                                  Voucher.DeliveryChannel channel, String campaign,
                                  BigDecimal value, String currency,
                                  Voucher.VoucherType type, int usageLimit) {
        if (value == null || value.signum() <= 0) {
            // Defence-in-depth behind the DTO @NotNull @Positive — a voucher's
            // face value is always money now (V45), so a missing or
            // non-positive one is never issuable.
            throw LoyaltyException.badRequest("MISSING_VALUE",
                    "A voucher's face value must be a positive amount.");
        }
        // Fail closed on currency, like every other write entry point that
        // accepts or defaults one: absent inherits the merchant's currency,
        // anything outside the cell's allowlist is refused.
        String resolvedCurrency = supportedCurrencies.requireSupported(
                currency != null && !currency.isBlank() ? currency : merchant.getCurrency());
        if (assignedUserId != null) {
            LoyaltyUser u = users.findById(assignedUserId)
                    .orElseThrow(() -> LoyaltyException.notFound("user"));
            if (!u.getTenantId().equals(tenantId)) {
                throw LoyaltyException.forbidden("CROSS_TENANT", "user belongs to a different tenant");
            }
            // BLANK counts as absent, not as a phone. This tested `== null`
            // only, so an explicitly blank assigneePhone ("" or " ") survived
            // into the row — and then every consumer disagreed about it:
            // holderPhone treats blank as absent and falls back to this user's
            // number (so the code was DELIVERED to them), while the ownership
            // check compared a live phone claim against the blank string and
            // refused the voucher to that same person. Normalising here is the
            // fix at the source; resolving the holder through holderPhone is the
            // fix for rows already written this way.
            if (assigneePhone == null || assigneePhone.isBlank()) assigneePhone = u.getPhoneNumber();
            // assigneeName is supplied by caller — loyalty-service does not
            // duplicate identity from user-service.
        } else if (assigneePhone != null && !assigneePhone.isBlank()) {
            // Phone-only path: auto-enrol a PENDING LoyaltyUser so the voucher
            // is linked to a real row from issue time. The promote-on-registration
            // webhook can then flip the user to ACTIVE without scanning vouchers
            // for unmatched phones.
            LoyaltyUser pending = userService.findOrCreatePending(tenantId, assigneePhone, merchant.getId());
            assignedUserId = pending.getId();
            // Store the canonical E.164 the LoyaltyUser now holds, not the raw
            // caller spelling, so voucher.assignee_phone + delivery both align.
            assigneePhone = pending.getPhoneNumber();
        }

        String code = uniqueCode();
        Voucher v = new Voucher();
        v.setTenantId(tenantId);
        v.setMerchantId(merchant.getId());
        v.setBatchId(batchId);
        v.setCode(code);
        v.setSignature(signer.sign(signPayload(tenantId, null, code)));
        v.setAssignedUserId(assignedUserId);
        v.setAssigneePhone(assigneePhone);
        // Caller-supplied display name — strip any HTML before persisting
        // (stored-XSS hardening). Null-safe; a no-op on legitimate names.
        v.setAssigneeName(HtmlSanitizer.stripAll(assigneeName));
        // Sender identity (V46) — who the voucher is FROM as it should read to
        // the recipient. The name is presentation, sanitised like assigneeName.
        // Distinct from the issuer_* audit columns below, which always come
        // from the JWT and never the body; the caller (issue) resolves the
        // default sender phone, so bulk stock stays sender-less.
        v.setSenderName(HtmlSanitizer.stripAll(senderName));
        v.setSenderPhone(senderPhone != null && !senderPhone.isBlank() ? senderPhone : null);
        // Stamp WHO issued it (and from which outlet) from the caller's JWT, so
        // reports carry a real issuer number alongside the receiver. All null
        // when there's no authenticated caller (internal / system issuance).
        v.setShopId(CallerDetails.currentShopId());
        v.setIssuerUserId(CallerDetails.currentUserId());
        v.setIssuerPhone(CallerDetails.currentPhoneNumber());
        v.setIssuerEmail(CallerDetails.currentEmail());
        v.setDeliveryChannel(channel);
        v.setCampaignSource(campaign);
        // The face value is frozen here — the worth at the moment of issue,
        // like an invoice line capturing the price at the moment of sale.
        // Always a money AMOUNT in an explicit currency since V45.
        v.setVoucherType(type);
        v.setValue(value);
        v.setCurrency(resolvedCurrency);
        // Multi-currency liability freeze (V38). An outstanding voucher is a
        // promise the platform hasn't paid yet, and that promise is priced when
        // it is MADE — so the USD worth is pinned here, at issuance, not
        // recomputed at redemption. Otherwise the outstanding-voucher book would
        // move every day on FX alone, with nothing issued and nothing redeemed.
        // Unconditional since V45: every voucher's value is money. A supported
        // currency with no in-force rate refuses (NO_FX_RATE) rather than
        // silently pricing at 1.0 — provision the rate before selling in it.
        ExchangeRateService.Conversion base =
                fx.toBaseWithRate(tenantId, value, resolvedCurrency);
        v.setBaseValue(base.amount());
        v.setFxRateId(base.rateId());
        v.setUsesRemaining(usageLimit);
        // Expiry is commercial config now (V45): merchant rule → tenant's
        // global rule → the platform default. Resolved per issue so a rule
        // change applies to the NEXT voucher, never retroactively.
        int validity = EffectiveFees.resolveVoucherValidityDays(
                rules.findApplicable(tenantId, merchant.getId(), TransactionType.PURCHASE),
                Instant.now(), defaultValidityDays);
        if (validity > 0) {
            v.setExpiresAt(Instant.now().plus(validity, ChronoUnit.DAYS));
        }
        return v;
    }

    /**
     * The HMAC payload behind every voucher signature. Pre-V45 vouchers were
     * signed over {@code tenant:templateId:code}; templates are retired, so
     * new vouchers sign over {@code tenant:-:code}. Verification recomputes
     * from the STORED row — legacy rows still carry their template id, so both
     * generations keep verifying with no re-signing pass. The stored
     * template id (or its absence) is exactly what the signature binds, which
     * is why verify sites must always pass {@code v.getTemplateId()}.
     */
    private static String signPayload(UUID tenantId, UUID templateId, String code) {
        return tenantId + ":" + (templateId == null ? "-" : templateId) + ":" + code;
    }

    private String uniqueCode() {
        for (int i = 0; i < 8; i++) {
            String code = CryptoSigner.randomVoucherCode(12);
            if (vouchers.findByCode(code).isEmpty()) return code;
        }
        throw new IllegalStateException("Failed to allocate unique voucher code");
    }

    // markDelivered(UUID) was removed in V48 with the DELIVERED status it set.
    // It had no HTTP endpoint and no caller anywhere in the service — nothing
    // ever promoted a voucher to DELIVERED after the fact, which is part of why
    // that status could only ever report an intention. The dispatch instant is
    // stamped once, in finishIssue, onto Voucher.deliveredAt.

    public void markViewed(String code) {
        vouchers.findByCode(code).ifPresent(v -> {
            // Only the voucher's owner (assignee) — or issuing/merchant staff — may
            // record a VIEW. Without this any authenticated principal could mark an
            // arbitrary code viewed and pollute issue→view analytics.
            requireCallerMayViewVoucher(v);
            if (v.getViewedAt() == null) {
                v.setViewedAt(Instant.now());
                // Reads like a tautology since V48 left ISSUED as the only
                // pre-view status, and is not: it stops a PARTIALLY_USED
                // voucher being DOWNGRADED to VIEWED by a later view event.
                if (v.getStatus() == Voucher.Status.ISSUED) {
                    v.setStatus(Voucher.Status.VIEWED);
                }
            }
        });
    }

    private void requireCallerMayViewVoucher(Voucher v) {
        // Issuing / merchant staff may record delivery + view lifecycle events.
        if (com.innbucks.loyaltyservice.security.CallerDetails.hasAnyRole(
                "ROLE_SUPER_ADMIN", "ROLE_MERCHANT_ADMIN", "ROLE_SHOP_ADMIN", "ROLE_SHOP_USER")) {
            return;
        }
        // Otherwise the caller must be the voucher's own holder — resolved via
        // holderPhone, not read off the raw assigneePhone column, for the same
        // reason as the redeem-side check: a voucher issued by assignedUserId
        // alone has a null column, and comparing a live phone claim against null
        // locked its own holder out of viewing and transferring it.
        String callerPhone = com.innbucks.loyaltyservice.security.CallerDetails.currentPhoneNumber();
        String holder = holderPhone(v);
        // Same two properties as the redeem-side check: a null caller phone and a
        // holder-less voucher both refuse, the latter through equals(null). Not
        // Objects.equals, which would pass two nulls.
        if (callerPhone == null || !callerPhone.equals(holder)) {
            throw LoyaltyException.forbidden("NOT_VOUCHER_OWNER",
                    "you can only act on your own vouchers");
        }
    }

    public Dtos.RedemptionResponse redeem(UUID tenantId, UUID merchantId, Dtos.RedeemVoucherRequest req) {
        // Timer captures end-to-end latency for the hottest read+write path in
        // the service. Exception paths are timed too (Timer.record records the
        // duration regardless), which is intentional — slow rejections matter.
        io.micrometer.core.instrument.Timer.Sample sample =
                io.micrometer.core.instrument.Timer.start();
        try {
            return doRedeem(tenantId, merchantId, req);
        } finally {
            sample.stop(metrics.redemptionLatency());
        }
    }

    private Dtos.RedemptionResponse doRedeem(UUID tenantId, UUID merchantId, Dtos.RedeemVoucherRequest req) {
        Voucher v = vouchers.lockByCode(req.code()).orElse(null);
        if (v == null || !v.getTenantId().equals(tenantId)) {
            fraud.record(tenantId, req.userId(), merchantId, req.code(),
                    FraudAttempt.Reason.INVALID_CODE, "voucher not found",
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.notFound("voucher");
        }

        String expectedSig = signer.sign(signPayload(tenantId, v.getTemplateId(), v.getCode()));
        if (!expectedSig.equals(v.getSignature())) {
            fraud.record(tenantId, req.userId(), merchantId, req.code(),
                    FraudAttempt.Reason.BAD_SIGNATURE, "tampered signature",
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.forbidden("BAD_SIGNATURE", "This voucher couldn't be verified — its signature is invalid.");
        }

        if (v.getExpiresAt() != null && Instant.now().isAfter(v.getExpiresAt())) {
            // The EXPIRED status flip used to be set on the managed entity right
            // here, and was then discarded by the throw below along with the
            // audit row — see rejectRedemption. It is now applied by the
            // after-rollback listener, which is also the only place it CAN be
            // applied: this transaction holds a PESSIMISTIC_WRITE lock on the
            // row, so a second transaction trying to update it would block on us
            // while we waited on it.
            rejectRedemption(v, merchantId, req, "expired", true);
            fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                    FraudAttempt.Reason.EXPIRED, "redemption after expiry",
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.badRequest("EXPIRED", "This voucher has expired.");
        }

        // REVOKED is checked BEFORE exhaustion, deliberately. Both are terminal,
        // but revocation is an operator decision and clients are documented to
        // branch on `code`: with the old order, a voucher revoked after its last
        // use answered ALREADY_REDEEMED, so a console showed "already redeemed"
        // for a voucher someone had deliberately cancelled. The more specific
        // fact wins. (revoke() only sets the status and never zeroes the uses, so
        // a revoked voucher with uses left answered REVOKED under either order.)
        if (v.getStatus() == Voucher.Status.REVOKED) {
            rejectRedemption(v, merchantId, req, "revoked");
            fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                    FraudAttempt.Reason.ALREADY_REDEEMED, "redemption of a revoked voucher",
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.conflict("REVOKED", "This voucher is no longer valid.");
        }
        if (v.getStatus() == Voucher.Status.REDEEMED || v.getUsesRemaining() <= 0) {
            rejectRedemption(v, merchantId, req, "already redeemed");
            fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                    FraudAttempt.Reason.ALREADY_REDEEMED, "duplicate redemption attempt",
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.conflict("ALREADY_REDEEMED", "This voucher has already been fully redeemed.");
        }

        if (v.getMerchantId() != null && !v.getMerchantId().equals(merchantId)) {
            fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                    FraudAttempt.Reason.WRONG_MERCHANT,
                    "expected " + v.getMerchantId() + " got " + merchantId,
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.forbidden("WRONG_MERCHANT", "This voucher can't be redeemed at this shop.");
        }

        // A genuine CUSTOMER caller may only redeem a voucher assigned to THEM.
        // Without this a customer who knows (or guesses) a code — e.g. one they
        // transferred away, whose old code they still remember — could redeem it
        // straight from their own app, the redeem-side twin of the transfer
        // rotation above. The check is scoped to real customers: staff / cashier
        // roles (SHOP_USER, SHOP_ADMIN, MERCHANT_ADMIN, SUPER_ADMIN) present the
        // code at the counter on the holder's behalf and carry no phone claim.
        //
        // Compared against the RESOLVED holder (holderPhone), not the raw
        // assigneePhone column: a voucher issued by assignedUserId alone has a
        // null column, and comparing a live phone claim against null refused the
        // voucher to the very customer it had just been delivered to.
        boolean staffCaller = CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN", "ROLE_MERCHANT_ADMIN",
                "ROLE_SHOP_ADMIN", "ROLE_SHOP_USER");
        if (CallerDetails.hasAnyRole("ROLE_CUSTOMER") && !staffCaller) {
            String callerPhone = CallerDetails.currentPhoneNumber();
            String holder = holderPhone(v);
            // A voucher with NO holder (bulk stock) refuses a customer bearer too,
            // via equals(null) being false — resolving the holder must not turn
            // "nobody owns this" into "everybody owns this". Do NOT rewrite this
            // as Objects.equals(callerPhone, holder): that is TRUE for two nulls
            // and would hand unassigned stock to any caller with no phone claim.
            if (callerPhone == null || !callerPhone.equals(holder)) {
                rejectRedemption(v, merchantId, req, "not voucher assignee");
                fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                        FraudAttempt.Reason.NOT_ASSIGNEE, "customer redeem of unassigned voucher",
                        req.deviceFingerprint(), req.ipAddress());
                throw LoyaltyException.forbidden("NOT_VOUCHER_OWNER",
                        "This voucher isn't assigned to you.");
            }
        }

        // The HOLDER's account state, resolved from the voucher — see
        // holderAccount for why this must not come from req.userId(), which is
        // recorded as a claim and nothing more. Empty for bulk stock, which has
        // no holder to check.
        //
        // The verdict comes from UserService.spendabilityOf, the same decision
        // the points spend gate uses, so this gate cannot drift from that one
        // again: it had lost the PENDING heal, the V44 on-demand eligibility
        // check and the INACTIVE refusal entirely. Only the WORDING is local —
        // VoucherController's 403 documentation promises callers that `message`
        // can be shown as-is, and a cashier reads these aloud to the person at
        // the counter, so they must describe a voucher rather than a points
        // balance and must not name a signup step the holder has no route to.
        java.util.Optional<LoyaltyUser> holder = holderAccount(v);
        if (holder.isPresent()) {
            switch (userService.spendabilityOf(holder.get())) {
                case OK -> { /* spendable */ }
                case BLOCKED -> {
                    rejectRedemption(v, merchantId, req, "holder account blocked");
                    fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                            FraudAttempt.Reason.BLOCKED_USER, "blocked holder attempted redemption",
                            req.deviceFingerprint(), req.ipAddress());
                    throw LoyaltyException.forbidden("USER_BLOCKED",
                            "This voucher's account is currently suspended. Please contact support.");
                }
                case PENDING_REGISTRATION -> {
                    rejectRedemption(v, merchantId, req, "holder pending registration");
                    throw LoyaltyException.forbidden("USER_PENDING",
                            "This voucher's rewards account is still being set up, so it can't be "
                                    + "redeemed yet.");
                }
                case INACTIVE -> {
                    rejectRedemption(v, merchantId, req, "holder account inactive");
                    throw LoyaltyException.forbidden("USER_INACTIVE",
                            "This voucher's account is inactive. Please contact support to "
                                    + "reactivate it.");
                }
            }
        }

        // Object-level authorization on the REDEEMING merchant — for STAFF only,
        // and the distinction is load-bearing rather than a shortcut.
        //
        // requireMerchant alone only proves the merchant exists in this tenant,
        // so a STAFF caller whose token carries no merchantId claim — a
        // multi-merchant MERCHANT_ADMIN, deliberately given none — could name a
        // merchant it does not administer and burn that merchant's voucher. A
        // claim-pinned caller (SHOP_ADMIN / SHOP_USER, and a single-merchant
        // MERCHANT_ADMIN) was never exposed, because the claim overrides the body
        // in CallerDetails.resolveMerchantId; this closes the caller who has no
        // claim to override it.
        //
        // A NON-staff caller must never be asked to administer the shop, because
        // it never can, and the condition is "is staff" rather than "is not a
        // customer" for a reason: two distinct non-staff shapes reach here.
        //   * A CUSTOMER redeeming their own voucher. Their authorization is the
        //     assignee check above plus WRONG_MERCHANT, which together already
        //     pin the redemption to this voucher's own merchant.
        //   * A caller with NO authentication at all — which is what
        //     PublicTestController.asCustomer leaves in place for UNASSIGNED
        //     bulk stock (it only installs a principal when the voucher has a
        //     holder).
        // Requiring administration of either refuses it `NOT_MERCHANT_OWNER`,
        // and the first draft of this change did exactly that to both.
        if (staffCaller) {
            merchantAuthz.requireCallerAdministersMerchant(tenantId, merchantId);
        } else {
            merchants.requireMerchant(tenantId, merchantId);
        }

        v.setUsesRemaining(v.getUsesRemaining() - 1);
        if (v.getUsesRemaining() <= 0) {
            v.setStatus(Voucher.Status.REDEEMED);
            v.setRedeemedAt(Instant.now());
        } else {
            v.setStatus(Voucher.Status.PARTIALLY_USED);
        }

        VoucherRedemption r = recordRedemption(v, merchantId, req, VoucherRedemption.Result.SUCCESS, null);
        metrics.incVouchersRedeemed();
        // Read the value straight off the voucher — snapshotted at issue time,
        // always a money amount since V45.
        return new Dtos.RedemptionResponse(r.getId(), v.getId(), v.getStatus().name(),
                v.getUsesRemaining(), v.getValue(), r.getRedeemedAt());
    }

    /**
     * Records a SUCCESSFUL redemption, inside the redeeming transaction — which
     * is exactly where it belongs: if the redemption rolls back, the row saying
     * it happened must roll back with it.
     *
     * <p>Refusals are the mirror image and go through
     * {@link #rejectRedemption} instead.
     */
    private VoucherRedemption recordRedemption(Voucher v, UUID merchantId, Dtos.RedeemVoucherRequest req,
                                               VoucherRedemption.Result result, String reason) {
        VoucherRedemption r = new VoucherRedemption();
        r.setTenantId(v.getTenantId());
        r.setVoucherId(v.getId());
        r.setUserId(req.userId());
        r.setMerchantId(merchantId);
        r.setOutletCode(req.outletCode());
        r.setIpAddress(req.ipAddress());
        r.setDeviceFingerprint(req.deviceFingerprint());
        r.setResult(result);
        r.setReason(reason);
        return redemptions.save(r);
    }

    private void rejectRedemption(Voucher v, UUID merchantId, Dtos.RedeemVoucherRequest req,
                                  String reason) {
        rejectRedemption(v, merchantId, req, reason, false);
    }

    /**
     * Records a REFUSED redemption. Every caller throws immediately afterwards,
     * so the row cannot be written here: it would roll back with the refusal it
     * exists to document, which is precisely what used to happen — six branches
     * wrote REJECTED rows and {@code voucher_redemptions} could only ever hold
     * SUCCESS.
     *
     * <p>Publishing instead defers the write to after this transaction has rolled
     * back and released the voucher's write lock. {@code
     * VoucherRedemptionRejectedEvent} explains why the lock rules out the
     * {@code REQUIRES_NEW} trick {@code FraudService.record} uses one line away.
     *
     * @param markExpired also flip the voucher to EXPIRED once the lock is gone —
     *                    the expiry branch only, honouring the Swagger's promise.
     */
    private void rejectRedemption(Voucher v, UUID merchantId, Dtos.RedeemVoucherRequest req,
                                  String reason, boolean markExpired) {
        events.publishEvent(new com.innbucks.loyaltyservice.integration.VoucherRedemptionRejectedEvent(
                v.getTenantId(), v.getId(), req.userId(), merchantId, req.outletCode(),
                req.ipAddress(), req.deviceFingerprint(), reason, markExpired));
    }

    public void revoke(UUID tenantId, UUID voucherId) {
        Voucher v = vouchers.findById(voucherId)
                .orElseThrow(() -> LoyaltyException.notFound("voucher"));
        if (!v.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        }
        // Per-merchant ownership guard, mirroring the redeem WRONG_MERCHANT check
        // (and RuleAdminService.deactivateRule). A merchant-scoped caller —
        // SHOP_ADMIN carries merchantId in the JWT — may only revoke its OWN
        // merchant's vouchers, so a SHOP_ADMIN for merchant B can't void
        // merchant A's voucher within the same tenant. MERCHANT_ADMIN /
        // SUPER_ADMIN are tenant-scoped (currentMerchantId() is null) and bypass,
        // already bounded by the tenant check above.
        UUID callerMerchantId = CallerDetails.currentMerchantId();
        if (v.getMerchantId() != null && callerMerchantId != null
                && !v.getMerchantId().equals(callerMerchantId)) {
            throw LoyaltyException.forbidden("WRONG_MERCHANT", "This voucher belongs to a different merchant.");
        }
        v.setStatus(Voucher.Status.REVOKED);
    }

    /**
     * Hand a voucher to another customer. <b>Single hop</b>: the lifecycle is
     * issued → transferred → redeemed, and the recipient of a transfer cannot
     * pass it on again.
     *
     * <p>Why single hop, since it is the whole point of this method: a voucher
     * carries a merchant's liability at a value frozen at issuance. A freely
     * circulating voucher becomes a bearer instrument — it can be sold on, and
     * the merchant loses any link between who was given the incentive and who
     * eventually redeems it, which is exactly what the issue-side fee is priced
     * against. One hop covers the real use case (I can't use this, take it)
     * without turning the voucher into currency.
     *
     * <p>The rule is enforced by {@code transferredAt != null}, under a
     * pessimistic lock. The lock is not optional: without it two concurrent
     * transfers of the same voucher could both read a null
     * {@code transferredAt}, and only the {@code @Version} check would stand
     * between them and a voucher delivered to two different people.
     */
    public Dtos.VoucherResponse transfer(UUID tenantId, UUID voucherId, Dtos.VoucherTransferRequest req) {
        boolean hasToUserId = req.toUserId() != null;
        boolean hasToPhone = req.toPhone() != null && !req.toPhone().isBlank();
        if (hasToUserId == hasToPhone) {
            throw LoyaltyException.badRequest("RECIPIENT_REQUIRED",
                    "supply exactly one of toUserId or toPhone");
        }

        Voucher v = vouchers.lockById(voucherId)
                .orElseThrow(() -> LoyaltyException.notFound("voucher"));
        if (!v.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        }
        // Only the current holder may pass it on. Staff roles are allowed
        // through the same helper the view/delivery lifecycle uses, so an
        // operator can move a voucher on a customer's behalf for support.
        requireCallerMayViewVoucher(v);

        // THE single-hop rule.
        if (v.getTransferredAt() != null) {
            throw LoyaltyException.badRequest("VOUCHER_ALREADY_TRANSFERRED",
                    "This voucher has already been transferred once and can't be passed on again.");
        }

        // Only a live, wholly-unused voucher can move. PARTIALLY_USED is
        // deliberately refused alongside the terminal states: the original
        // holder has already consumed part of the value, so handing over the
        // remainder splits one voucher's benefit across two people and makes
        // the redemption trail ambiguous about who received what.
        if (v.getStatus() != Voucher.Status.ISSUED
                && v.getStatus() != Voucher.Status.VIEWED) {
            throw LoyaltyException.badRequest("VOUCHER_NOT_TRANSFERABLE",
                    "Only an unused voucher can be transferred (this one is " + v.getStatus() + ").");
        }
        if (v.getExpiresAt() != null && v.getExpiresAt().isBefore(Instant.now())) {
            throw LoyaltyException.badRequest("VOUCHER_EXPIRED",
                    "This voucher has expired and can't be transferred.");
        }

        var recipient = hasToUserId
                ? userService.require(tenantId, req.toUserId())
                : userService.findOrCreatePending(tenantId, req.toPhone(), v.getMerchantId());

        // Compare on phone, not user id: a customer can hold one LoyaltyUser
        // projection per tenant, so id-equality alone would let someone
        // "transfer" a voucher to themselves via a sibling projection.
        if (recipient.getPhoneNumber().equals(v.getAssigneePhone())
                || (v.getAssignedUserId() != null && v.getAssignedUserId().equals(recipient.getId()))) {
            throw LoyaltyException.badRequest("SELF_TRANSFER",
                    "You can't transfer a voucher to yourself.");
        }

        UUID fromUserId = v.getAssignedUserId();
        String fromPhone = v.getAssigneePhone();

        v.setTransferredAt(Instant.now());
        v.setTransferredFromUserId(fromUserId);
        v.setTransferredFromPhone(fromPhone);

        v.setAssignedUserId(recipient.getId());
        v.setAssigneePhone(recipient.getPhoneNumber());
        // The note is the sender's, not the recipient's name — don't let it
        // overwrite assigneeName with something that isn't a name.
        v.setAssigneeName(null);

        // Rotate the code + recompute its signature on transfer. Reassigning the
        // voucher above changes WHO owns it, but the old code was already in the
        // SENDER's hands — they saw it in-app and (for delivered vouchers) got it
        // by WhatsApp/SMS at issuance. Left unchanged, the previous holder could
        // still walk into a shop and redeem a voucher they've given away, draining
        // the value from the person they handed it to (redeem is keyed by the code,
        // not by who presents it). Minting a fresh code and re-signing it makes the
        // sender's copy dead the instant this transfer commits; only the new
        // assignee can retrieve the new code — they're now the voucher's owner for
        // requireCallerMayViewVoucher and for the tenant-scoped activeForPhone the
        // customer app reads.
        String rotatedCode = uniqueCode();
        v.setCode(rotatedCode);
        v.setSignature(signer.sign(signPayload(tenantId, v.getTemplateId(), rotatedCode)));

        // Clear the pre-expiry warning stamp. It records that the PREVIOUS
        // holder was warned; leaving it set would make ExpiryWarningSweeper skip
        // the new holder entirely, so they'd never hear the voucher was about to
        // lapse. The lifecycle timestamps (delivered/viewed) are left alone —
        // they are history, and history is not the new holder's to reset.
        v.setExpiryWarnedAt(null);

        log.info("Voucher transferred voucherId={} from={} to={} tenantId={}",
                v.getId(), MsisdnMasking.mask(fromPhone),
                MsisdnMasking.mask(recipient.getPhoneNumber()), tenantId);

        // Both sides are told. Without the RECEIVED message the transfer is
        // silent: the voucher lands in a wallet the recipient has no reason to
        // open, and vouchers still expire (365d default) even though points no
        // longer do — so a silent transfer can simply lapse unused, which
        // defeats the whole point of handing it over. The SENT message is the
        // sender's only confirmation that a phone number they typed by hand
        // resolved to the person they meant.
        //
        // Both are @Async and best-effort: a notification failure must never
        // roll back a transfer that has already happened.
        java.time.LocalDate expiresOn = v.getExpiresAt() == null
                ? null
                : v.getExpiresAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        memberNotifier.notifyVoucherReceived(recipient.getPhoneNumber(),
                v.getValue(), v.getCurrency(), expiresOn);
        memberNotifier.notifyVoucherSent(fromPhone, v.getValue(), v.getCurrency());

        // Hand the ROTATED code to the new holder the same way issuance does —
        // best-effort WhatsApp/SMS. The sender's old code is now dead, so the
        // recipient needs a route to the new one; the in-app view (activeForPhone
        // → toResponse) is the primary path, this is the out-of-band mirror.
        notifications.deliver(v, recipient.getPhoneNumber());

        // Redact the code from the transfer RESPONSE. The response goes to the
        // CALLER — the sender (a CUSTOMER handing off their own voucher) or staff
        // acting on their behalf — neither of whom should now hold the rotated
        // code, or the rotation above would be pointless. The recipient reads it
        // in-app as the voucher's new assignee.
        return redactCode(toResponse(v));
    }

    /** Copy of a VoucherResponse with the {@code code} nulled out — used where the
     *  caller must not see the code (e.g. the sender's view of a transfer they just
     *  made, after the code has been rotated to the recipient). */
    private static Dtos.VoucherResponse redactCode(Dtos.VoucherResponse r) {
        return new Dtos.VoucherResponse(r.id(), null, r.status(), r.voucherType(),
                r.assignedUserId(), r.assigneePhone(),
                r.senderName(), r.senderPhone(), r.usesRemaining(),
                r.value(), r.currency(), r.issuedAt(), r.expiresAt(),
                r.baseValue());
    }

    @Transactional(readOnly = true)
    public List<Dtos.VoucherResponse> activeForUser(UUID userId) {
        return vouchers.findByAssignedUserIdAndStatusIn(userId, Voucher.LIVE_STATUSES)
                .stream().map(VoucherService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<Dtos.VoucherResponse> activeForUser(UUID userId, Pageable pageable) {
        return vouchers.findByAssignedUserIdAndStatusIn(userId, Voucher.LIVE_STATUSES, pageable)
                .map(VoucherService::toResponse);
    }

    /**
     * Active vouchers for a phone, scoped STRICTLY to the caller's tenant
     * (X-Tenant-Id). A phone can have a LoyaltyUser projection in several
     * tenants, but this endpoint must only ever return vouchers that belong to
     * the tenant on the request — resolving the phone's user via the
     * tenant-keyed unique lookup ({@code (tenantId, phoneNumber)}) guarantees
     * that. Without this scoping any cashier/admin in one tenant could
     * enumerate a customer's voucher codes and values across every tenant on
     * the platform (OWASP A01 cross-tenant enumeration). Returns an empty page
     * when the phone has no projection in this tenant (rather than 404) so the
     * customer-app UI can render "no vouchers yet" cleanly.
     */
    @Transactional(readOnly = true)
    public Page<Dtos.VoucherResponse> activeForPhone(UUID tenantId, String phoneNumber, Pageable pageable) {
        return users.findByTenantIdAndPhoneNumber(tenantId, phoneNumber)
                .map(u -> vouchers.findByAssignedUserIdAndStatusIn(u.getId(), Voucher.LIVE_STATUSES, pageable)
                        .map(VoucherService::toResponse))
                .orElseGet(() -> org.springframework.data.domain.Page.empty(pageable));
    }

    @Transactional(readOnly = true)
    public List<Dtos.VoucherResponse> findByStatus(UUID tenantId, Voucher.Status status) {
        return vouchers.findByTenantIdAndStatus(tenantId, status).stream()
                .map(VoucherService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<Dtos.VoucherResponse> findByStatus(UUID tenantId, Voucher.Status status, Pageable pageable) {
        return vouchers.findByTenantIdAndStatus(tenantId, status, pageable)
                .map(VoucherService::toResponse);
    }

    public static Dtos.VoucherResponse toResponse(Voucher v) {
        return new Dtos.VoucherResponse(v.getId(), v.getCode(), v.getStatus().name(),
                v.getVoucherType() == null ? null : v.getVoucherType().name(),
                v.getAssignedUserId(), v.getAssigneePhone(),
                v.getSenderName(), v.getSenderPhone(),
                v.getUsesRemaining(),
                v.getValue(), v.getCurrency(),
                v.getIssuedAt(), v.getExpiresAt(), v.getBaseValue());
    }
}
