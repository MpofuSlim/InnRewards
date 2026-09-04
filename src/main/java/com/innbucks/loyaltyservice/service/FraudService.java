package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.repository.FraudAttemptRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);

    private final FraudAttemptRepository fraud;
    private final LoyaltyUserRepository users;
    private final LoyaltyProperties props;
    private final com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics;

    public FraudService(FraudAttemptRepository fraud,
                        LoyaltyUserRepository users,
                        LoyaltyProperties props,
                        com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics) {
        this.fraud = fraud;
        this.users = users;
        this.props = props;
        this.metrics = metrics;
    }

    // REQUIRES_NEW is load-bearing, not an optimisation. Every caller follows
    // the pattern "record the attempt, then THROW the rejection" from inside a
    // @Transactional service — and a plain joined transaction meant the fraud
    // row (and even the velocity auto-block below) ROLLED BACK with the
    // rejection it was recording. The controller docs promise "failed attempts
    // are recorded in fraud_attempts"; without this, none of them were. A
    // separate transaction commits the evidence regardless of the caller's
    // outcome, at the standard REQUIRES_NEW cost of a second pooled connection
    // for its duration.
    /**
     * Records a rejected attempt as evidence, and applies the velocity auto-block
     * where it can be aimed correctly (see {@link #blockCallerIfSelfService}).
     *
     * <p><b>{@code userId} is what the request CLAIMED, not an attribution.</b>
     * Several callers pass a raw body field, so the stored value may name someone
     * who had nothing to do with the attempt. It is kept because a forensic
     * record should capture what was submitted — but never read a
     * {@code fraud_attempts.user_id} as "this person did it", and in particular
     * never block an account on the strength of one without checking the rest of
     * the row first.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public FraudAttempt record(UUID tenantId, UUID userId, UUID merchantId, String voucherCode,
                               FraudAttempt.Reason reason, String detail,
                               String deviceFingerprint, String ipAddress) {
        FraudAttempt fa = new FraudAttempt();
        fa.setTenantId(tenantId);
        fa.setUserId(userId);
        fa.setMerchantId(merchantId);
        fa.setVoucherCode(voucherCode);
        fa.setReason(reason);
        fa.setDetail(detail);
        fa.setDeviceFingerprint(deviceFingerprint);
        fa.setIpAddress(ipAddress);
        fraud.save(fa);
        metrics.incFraudRejected(reason.name());

        if (deviceFingerprint != null) {
            Instant since = Instant.now().minusSeconds(props.voucher().fraudWindowSeconds());
            long count = fraud.countByDeviceFingerprintAndCreatedAtAfter(deviceFingerprint, since);
            if (count >= props.voucher().fraudVelocityThreshold()) {
                blockCallerIfSelfService(count, deviceFingerprint);
            }
        }
        return fa;
    }

    /**
     * Blocks the AUTHENTICATED CALLER's own loyalty account — and nobody else's.
     *
     * <p><b>This used to block {@code userId}, the parameter above.</b> On the
     * voucher-redeem path that value is {@code req.userId()}: a raw body field,
     * passed into {@code record()} on the very first branch of
     * {@code VoucherService.doRedeem}, before the voucher is known to exist and
     * before any ownership, assignee or merchant check runs. The lookup carried
     * no tenant check either. So five malformed redeem calls naming a victim's
     * UUID, from one device fingerprint the attacker also chooses, flipped that
     * victim to BLOCKED — across any tenant, unspendable, and with no code path
     * anywhere in this service that transitions a row back out of BLOCKED.
     *
     * <p>The rule now is the one that should always have applied to a punitive
     * action: <b>act only on an identity the request has PROVEN</b>. The subject
     * is resolved from the security context, never from the request body, and is
     * then checked against the phone claim the ownership guards elsewhere rely
     * on — so a malformed or stale token cannot name someone else either.
     *
     * <p><b>Deliberate narrowing: a staff-operated till now blocks nobody.</b>
     * The velocity signal is keyed by DEVICE, and at a till the device belongs to
     * the shop while the person presenting bad codes is a customer. Blocking the
     * cashier's loyalty account for that was never right, and it handed any
     * customer a way to disable a member of staff. Same for the S2S paths, which
     * carry no customer principal at all. Those attempts are still recorded and
     * still counted — {@code fraud_attempts} and
     * {@code loyalty.fraud.rejected} are how an operator sees an attack — but the
     * automatic punishment is withheld where it cannot be aimed correctly.
     */
    private void blockCallerIfSelfService(long count, String deviceFingerprint) {
        // Staff and admins act on other people's behalf, so "the caller" is not
        // the person presenting codes. Only a plain customer is both.
        if (!CallerDetails.hasAnyRole("ROLE_CUSTOMER")
                || CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN", "ROLE_MERCHANT_ADMIN",
                        "ROLE_SHOP_ADMIN", "ROLE_SHOP_USER")) {
            log.warn("Velocity threshold hit ({} attempts from device {}) but the caller is not a "
                    + "self-service customer — recording only, blocking nobody", count, deviceFingerprint);
            return;
        }
        UUID callerId = CallerDetails.currentUserId();
        String callerPhone = CallerDetails.currentPhoneNumber();
        if (callerId == null || callerPhone == null) {
            log.warn("Velocity threshold hit ({} attempts from device {}) but the caller carries no "
                    + "usable identity — recording only, blocking nobody", count, deviceFingerprint);
            return;
        }
        users.findById(callerId).ifPresent(u -> {
            // The token said this id; the row has to agree it is the same person.
            // Mirrors UserService.requireCallerOwns, which is what every other
            // act-on-your-own-account check in this service uses.
            if (!callerPhone.equals(u.getPhoneNumber())) {
                log.warn("Velocity threshold hit but the caller's userId {} does not match their phone "
                        + "claim — recording only, blocking nobody", callerId);
                return;
            }
            if (u.getStatus() != LoyaltyUser.Status.BLOCKED) {
                u.setStatus(LoyaltyUser.Status.BLOCKED);
                log.warn("Auto-blocking caller {} after {} attempts from device {}",
                        callerId, count, deviceFingerprint);
            }
        });
    }
}
