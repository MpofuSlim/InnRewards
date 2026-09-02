package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherBatch;
import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
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
    private final VoucherTemplateService templateService;
    private final MerchantService merchants;
    private final LoyaltyUserRepository users;
    private final UserService userService;
    private final NotificationGateway notifications;
    private final FraudService fraud;
    private final com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics;
    private final com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier;
    private final CryptoSigner signer;
    private final ExchangeRateService fx;

    public VoucherService(VoucherRepository vouchers,
                          VoucherBatchRepository batches,
                          VoucherRedemptionRepository redemptions,
                          VoucherTemplateService templateService,
                          MerchantService merchants,
                          LoyaltyUserRepository users,
                          UserService userService,
                          NotificationGateway notifications,
                          FraudService fraud,
                          com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics,
                          com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier,
                          LoyaltyProperties props,
                          ExchangeRateService fx) {
        this.vouchers = vouchers;
        this.batches = batches;
        this.redemptions = redemptions;
        this.templateService = templateService;
        this.merchants = merchants;
        this.users = users;
        this.userService = userService;
        this.notifications = notifications;
        this.fraud = fraud;
        this.metrics = metrics;
        this.memberNotifier = memberNotifier;
        this.signer = new CryptoSigner(props.voucher().secret());
        this.fx = fx;
    }

    public Dtos.VoucherResponse issue(UUID tenantId, Dtos.IssueVoucherRequest req) {
        VoucherTemplate tpl = templateService.require(tenantId, req.templateId());
        BigDecimal value = requireValueIfNumeric(tpl, req.value());
        Voucher v = createFromTemplate(tenantId, tpl, null,
                req.assignedUserId(), req.assigneePhone(), req.assigneeName(),
                req.deliveryChannel(), req.campaignSource(), value,
                req.usesOverride(), req.validityDaysOverride());
        vouchers.save(v);
        // Flip status on THIS thread (before the async hand-off reads the entity
        // on the executor thread). Optimistic best-effort: DELIVERED means "we
        // dispatched it"; the actual WhatsApp/SMS send runs off the request
        // thread so a slow gateway never blocks voucher issuance.
        if (v.getDeliveryChannel() != null && v.getDeliveryChannel() != Voucher.DeliveryChannel.NONE) {
            v.setStatus(Voucher.Status.DELIVERED);
            v.setDeliveredAt(Instant.now());
        }
        notifications.deliver(v, resolveDeliveryPhone(v));
        metrics.incVouchersIssued();
        return toResponse(v);
    }

    public List<Dtos.VoucherResponse> issueBulk(UUID tenantId, Dtos.BulkIssueRequest req) {
        VoucherTemplate tpl = templateService.require(tenantId, req.templateId());
        BigDecimal value = requireValueIfNumeric(tpl, req.value());
        VoucherBatch batch = new VoucherBatch();
        batch.setTenantId(tenantId);
        batch.setTemplateId(tpl.getId());
        batch.setQuantity(req.quantity());
        batch.setCampaign(req.campaign());
        batches.save(batch);

        List<Dtos.VoucherResponse> result = new ArrayList<>(req.quantity());
        for (int i = 0; i < req.quantity(); i++) {
            Voucher v = createFromTemplate(tenantId, tpl, batch.getId(),
                    null, null, null, req.deliveryChannel(),
                    req.campaign(), value, null, null);
            vouchers.save(v);
            result.add(toResponse(v));
        }
        metrics.incVouchersIssued(req.quantity());
        return result;
    }

    /**
     * AMOUNT and PERCENT vouchers are meaningless without a numeric value
     * and we used to enforce this at template-create time. After the
     * value-on-issuance refactor (V14), enforcement moves to the issue
     * call site — the template only declares the *shape* of the value.
     * FREE_ITEM / COMBO templates ignore the field entirely and pass
     * null through.
     */
    /**
     * The phone we can actually reach the recipient on: the explicit assignee
     * phone, else the assigned LoyaltyUser's phone. Null when the voucher has no
     * reachable phone (e.g. a bulk / unassigned voucher) — the gateway then just
     * logs and skips. Best-effort resolution; never throws.
     */
    private String resolveDeliveryPhone(Voucher v) {
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

    private static BigDecimal requireValueIfNumeric(VoucherTemplate tpl, BigDecimal value) {
        VoucherTemplate.ValueType vt = tpl.getValueType();
        if ((vt == VoucherTemplate.ValueType.AMOUNT || vt == VoucherTemplate.ValueType.PERCENT)
                && value == null) {
            throw LoyaltyException.badRequest("MISSING_VALUE",
                    vt.name() + " vouchers require value at issue time");
        }
        return value;
    }

    private Voucher createFromTemplate(UUID tenantId, VoucherTemplate tpl, UUID batchId,
                                       UUID assignedUserId, String assigneePhone, String assigneeName,
                                       Voucher.DeliveryChannel channel, String campaign,
                                       BigDecimal value,
                                       Integer usesOverride, Integer validityOverride) {
        if (assignedUserId != null) {
            LoyaltyUser u = users.findById(assignedUserId)
                    .orElseThrow(() -> LoyaltyException.notFound("user"));
            if (!u.getTenantId().equals(tenantId)) {
                throw LoyaltyException.forbidden("CROSS_TENANT", "user belongs to a different tenant");
            }
            if (assigneePhone == null) assigneePhone = u.getPhoneNumber();
            // assigneeName is supplied by caller — loyalty-service does not
            // duplicate identity from user-service.
        } else if (assigneePhone != null && !assigneePhone.isBlank()) {
            // Phone-only path: auto-enrol a PENDING LoyaltyUser so the voucher
            // is linked to a real row from issue time. The promote-on-registration
            // webhook can then flip the user to ACTIVE without scanning vouchers
            // for unmatched phones.
            LoyaltyUser pending = userService.findOrCreatePending(tenantId, assigneePhone, tpl.getMerchantId());
            assignedUserId = pending.getId();
            // Store the canonical E.164 the LoyaltyUser now holds, not the raw
            // caller spelling, so voucher.assignee_phone + delivery both align.
            assigneePhone = pending.getPhoneNumber();
        }

        String code = uniqueCode();
        Voucher v = new Voucher();
        v.setTenantId(tenantId);
        v.setMerchantId(tpl.getMerchantId());
        v.setTemplateId(tpl.getId());
        v.setBatchId(batchId);
        v.setCode(code);
        v.setSignature(signer.sign(tenantId + ":" + tpl.getId() + ":" + code));
        v.setAssignedUserId(assignedUserId);
        v.setAssigneePhone(assigneePhone);
        // Caller-supplied display name — strip any HTML before persisting
        // (stored-XSS hardening). Null-safe; a no-op on legitimate names.
        v.setAssigneeName(HtmlSanitizer.stripAll(assigneeName));
        // Stamp WHO issued it (and from which outlet) from the caller's JWT, so
        // reports carry a real issuer number alongside the receiver. All null
        // when there's no authenticated caller (internal / system issuance).
        v.setShopId(CallerDetails.currentShopId());
        v.setIssuerUserId(CallerDetails.currentUserId());
        v.setIssuerPhone(CallerDetails.currentPhoneNumber());
        v.setIssuerEmail(CallerDetails.currentEmail());
        v.setDeliveryChannel(channel);
        v.setCampaignSource(campaign);
        // Snapshot the caller-supplied value onto the voucher. The template
        // dictates the *shape* (AMOUNT, PERCENT, FREE_ITEM, COMBO); the
        // numeric value itself is set per issuance, so a "Coffee voucher"
        // template can be issued at $5 or $10 without spinning up two
        // templates. Once stamped here it's frozen — the original value at
        // issuance time — so a later template edit won't retroactively
        // change the worth of vouchers already in customers' hands.
        v.setValueType(tpl.getValueType());
        v.setValue(value);
        v.setCurrency(tpl.getCurrency());
        // Multi-currency liability freeze (V38). An outstanding voucher is a
        // promise the platform hasn't paid yet, and that promise is priced when
        // it is MADE — so the USD worth is pinned here, at issuance, not
        // recomputed at redemption. Otherwise the outstanding-voucher book would
        // move every day on FX alone, with nothing issued and nothing redeemed.
        //
        // ONLY for AMOUNT: a PERCENT voucher's value is a percentage and a
        // FREE_ITEM/COMBO has no money face value at all. "10% off" is not 10 of
        // anything, so converting it would mint a confident, meaningless
        // liability figure — those stay null by design.
        if (tpl.getValueType() == VoucherTemplate.ValueType.AMOUNT
                && value != null && value.signum() >= 0) {
            ExchangeRateService.Conversion base =
                    fx.toBaseWithRate(tenantId, value, v.getCurrency());
            v.setBaseValue(base.amount());
            v.setFxRateId(base.rateId());
        }
        int uses = usesOverride != null ? usesOverride : tpl.getUsageLimit();
        v.setUsesRemaining(Math.max(1, uses));
        Integer validity = validityOverride != null ? validityOverride : tpl.getValidityDays();
        if (validity != null && validity > 0) {
            v.setExpiresAt(Instant.now().plus(validity, ChronoUnit.DAYS));
        }
        return v;
    }

    private String uniqueCode() {
        for (int i = 0; i < 8; i++) {
            String code = CryptoSigner.randomVoucherCode(12);
            if (vouchers.findByCode(code).isEmpty()) return code;
        }
        throw new IllegalStateException("Failed to allocate unique voucher code");
    }

    public void markDelivered(UUID voucherId) {
        Voucher v = vouchers.findById(voucherId)
                .orElseThrow(() -> LoyaltyException.notFound("voucher"));
        v.setStatus(Voucher.Status.DELIVERED);
        v.setDeliveredAt(Instant.now());
    }

    public void markViewed(String code) {
        vouchers.findByCode(code).ifPresent(v -> {
            // Only the voucher's owner (assignee) — or issuing/merchant staff — may
            // record a VIEW. Without this any authenticated principal could mark an
            // arbitrary code viewed and pollute delivery→view analytics.
            requireCallerMayViewVoucher(v);
            if (v.getViewedAt() == null) {
                v.setViewedAt(Instant.now());
                if (v.getStatus() == Voucher.Status.ISSUED || v.getStatus() == Voucher.Status.DELIVERED) {
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
        // Otherwise the caller must be the voucher's own assignee.
        String callerPhone = com.innbucks.loyaltyservice.security.CallerDetails.currentPhoneNumber();
        if (callerPhone == null || !callerPhone.equals(v.getAssigneePhone())) {
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

        String expectedSig = signer.sign(tenantId + ":" + v.getTemplateId() + ":" + v.getCode());
        if (!expectedSig.equals(v.getSignature())) {
            fraud.record(tenantId, req.userId(), merchantId, req.code(),
                    FraudAttempt.Reason.BAD_SIGNATURE, "tampered signature",
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.forbidden("BAD_SIGNATURE", "This voucher couldn't be verified — its signature is invalid.");
        }

        if (v.getExpiresAt() != null && Instant.now().isAfter(v.getExpiresAt())) {
            v.setStatus(Voucher.Status.EXPIRED);
            VoucherRedemption rj = recordRedemption(v, merchantId, req, VoucherRedemption.Result.REJECTED, "expired");
            fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                    FraudAttempt.Reason.EXPIRED, "redemption after expiry",
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.badRequest("EXPIRED", "This voucher has expired.");
        }

        if (v.getStatus() == Voucher.Status.REDEEMED || v.getUsesRemaining() <= 0) {
            recordRedemption(v, merchantId, req, VoucherRedemption.Result.REJECTED, "already redeemed");
            fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                    FraudAttempt.Reason.ALREADY_REDEEMED, "duplicate redemption attempt",
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.conflict("ALREADY_REDEEMED", "This voucher has already been fully redeemed.");
        }
        if (v.getStatus() == Voucher.Status.REVOKED) {
            recordRedemption(v, merchantId, req, VoucherRedemption.Result.REJECTED, "revoked");
            throw LoyaltyException.conflict("REVOKED", "This voucher is no longer valid.");
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
        // code at the counter on the holder's behalf and carry no phone claim, and
        // the S2S / no-context redemption paths (shop-checkout, QR consume) run
        // without an authenticated CUSTOMER — all of those keep the bearer flow.
        if (CallerDetails.hasAnyRole("ROLE_CUSTOMER")
                && !CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN", "ROLE_MERCHANT_ADMIN",
                        "ROLE_SHOP_ADMIN", "ROLE_SHOP_USER")) {
            String callerPhone = CallerDetails.currentPhoneNumber();
            if (callerPhone == null || !callerPhone.equals(v.getAssigneePhone())) {
                recordRedemption(v, merchantId, req, VoucherRedemption.Result.REJECTED, "not voucher assignee");
                fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                        FraudAttempt.Reason.NOT_ASSIGNEE, "customer redeem of unassigned voucher",
                        req.deviceFingerprint(), req.ipAddress());
                throw LoyaltyException.forbidden("NOT_VOUCHER_OWNER",
                        "This voucher isn't assigned to you.");
            }
        }

        if (req.userId() != null) {
            LoyaltyUser u = users.findById(req.userId()).orElse(null);
            if (u != null && u.getStatus() == LoyaltyUser.Status.BLOCKED) {
                recordRedemption(v, merchantId, req, VoucherRedemption.Result.REJECTED, "user blocked");
                fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                        FraudAttempt.Reason.BLOCKED_USER, "blocked user attempted redemption",
                        req.deviceFingerprint(), req.ipAddress());
                throw LoyaltyException.forbidden("USER_BLOCKED", "Your account is currently suspended. Please contact support.");
            }
            // PENDING means the recipient hasn't registered yet — they can hold
            // the voucher but not redeem it. The "promote" webhook flips them
            // to ACTIVE the moment user-service confirms signup.
            if (u != null && u.getStatus() == LoyaltyUser.Status.PENDING) {
                recordRedemption(v, merchantId, req, VoucherRedemption.Result.REJECTED, "user pending registration");
                // Customer-safe prose: VoucherController's 403 documentation
                // promises callers that `message` can be shown as-is, and a
                // cashier reads this one off the till to the person holding the
                // voucher.
                throw LoyaltyException.forbidden("USER_PENDING",
                        "This voucher belongs to a phone number that isn't registered yet. "
                                + "The recipient needs to finish signing up before it can be redeemed.");
            }
        }

        merchants.requireMerchant(tenantId, merchantId);

        v.setUsesRemaining(v.getUsesRemaining() - 1);
        if (v.getUsesRemaining() <= 0) {
            v.setStatus(Voucher.Status.REDEEMED);
            v.setRedeemedAt(Instant.now());
        } else {
            v.setStatus(Voucher.Status.PARTIALLY_USED);
        }

        VoucherRedemption r = recordRedemption(v, merchantId, req, VoucherRedemption.Result.SUCCESS, null);
        metrics.incVouchersRedeemed();
        // Read value/valueType straight off the voucher — they were
        // snapshotted at issue time and the template is no longer the
        // source of truth for them.
        return new Dtos.RedemptionResponse(r.getId(), v.getId(), v.getStatus().name(),
                v.getUsesRemaining(), v.getValue(),
                v.getValueType() == null ? null : v.getValueType().name(),
                r.getRedeemedAt());
    }

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
                && v.getStatus() != Voucher.Status.DELIVERED
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
        v.setSignature(signer.sign(tenantId + ":" + v.getTemplateId() + ":" + rotatedCode));

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
        String valueType = v.getValueType() == null ? null : v.getValueType().name();
        java.time.LocalDate expiresOn = v.getExpiresAt() == null
                ? null
                : v.getExpiresAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        memberNotifier.notifyVoucherReceived(recipient.getPhoneNumber(), valueType,
                v.getValue(), v.getCurrency(), expiresOn);
        memberNotifier.notifyVoucherSent(fromPhone, valueType, v.getValue(), v.getCurrency());

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
        return new Dtos.VoucherResponse(r.id(), null, r.status(), r.templateId(),
                r.assignedUserId(), r.assigneePhone(), r.usesRemaining(),
                r.valueType(), r.value(), r.currency(), r.issuedAt(), r.expiresAt(),
                r.baseValue());
    }

    @Transactional(readOnly = true)
    public List<Dtos.VoucherResponse> activeForUser(UUID userId) {
        return vouchers.findByAssignedUserIdAndStatusIn(userId, List.of(
                Voucher.Status.ISSUED, Voucher.Status.DELIVERED, Voucher.Status.VIEWED,
                Voucher.Status.PARTIALLY_USED))
                .stream().map(VoucherService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<Dtos.VoucherResponse> activeForUser(UUID userId, Pageable pageable) {
        return vouchers.findByAssignedUserIdAndStatusIn(userId, List.of(
                Voucher.Status.ISSUED, Voucher.Status.DELIVERED, Voucher.Status.VIEWED,
                Voucher.Status.PARTIALLY_USED), pageable)
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
                .map(u -> vouchers.findByAssignedUserIdAndStatusIn(u.getId(), List.of(
                                Voucher.Status.ISSUED, Voucher.Status.DELIVERED, Voucher.Status.VIEWED,
                                Voucher.Status.PARTIALLY_USED), pageable)
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
                v.getTemplateId(), v.getAssignedUserId(), v.getAssigneePhone(),
                v.getUsesRemaining(),
                v.getValueType() == null ? null : v.getValueType().name(),
                v.getValue(), v.getCurrency(),
                v.getIssuedAt(), v.getExpiresAt(), v.getBaseValue());
    }
}
