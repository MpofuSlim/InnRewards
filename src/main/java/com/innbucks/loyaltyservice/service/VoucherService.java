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
import com.innbucks.loyaltyservice.security.CryptoSigner;
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
    private final CryptoSigner signer;

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
                          LoyaltyProperties props) {
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
        this.signer = new CryptoSigner(props.voucher().secret());
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
        v.setAssigneeName(assigneeName);
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
            if (v.getViewedAt() == null) {
                v.setViewedAt(Instant.now());
                if (v.getStatus() == Voucher.Status.ISSUED || v.getStatus() == Voucher.Status.DELIVERED) {
                    v.setStatus(Voucher.Status.VIEWED);
                }
            }
        });
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
                throw LoyaltyException.forbidden("USER_PENDING",
                        "voucher belongs to an unregistered phone; recipient must register before redeeming");
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
        v.setStatus(Voucher.Status.REVOKED);
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
     * Aggregate every active voucher for a phone — across every tenant the
     * phone has a LoyaltyUser projection in. Returns an empty page when the
     * phone has no projections at all (rather than 404) so the customer-app
     * UI can render "no vouchers yet" cleanly.
     */
    @Transactional(readOnly = true)
    public Page<Dtos.VoucherResponse> activeForPhone(String phoneNumber, Pageable pageable) {
        java.util.List<UUID> userIds = users.findByPhoneNumber(phoneNumber).stream()
                .map(com.innbucks.loyaltyservice.entity.LoyaltyUser::getId)
                .toList();
        if (userIds.isEmpty()) {
            return org.springframework.data.domain.Page.empty(pageable);
        }
        return vouchers.findByAssignedUserIdInAndStatusIn(userIds, List.of(
                Voucher.Status.ISSUED, Voucher.Status.DELIVERED, Voucher.Status.VIEWED,
                Voucher.Status.PARTIALLY_USED), pageable)
                .map(VoucherService::toResponse);
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
                v.getIssuedAt(), v.getExpiresAt());
    }
}
