package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.repository.FraudAttemptRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
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

    @Transactional
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

        if (deviceFingerprint != null && userId != null) {
            Instant since = Instant.now().minusSeconds(props.voucher().fraudWindowSeconds());
            long count = fraud.countByDeviceFingerprintAndCreatedAtAfter(deviceFingerprint, since);
            if (count >= props.voucher().fraudVelocityThreshold()) {
                users.findById(userId).ifPresent(u -> {
                    if (u.getStatus() != LoyaltyUser.Status.BLOCKED) {
                        u.setStatus(LoyaltyUser.Status.BLOCKED);
                        log.warn("Auto-blocking user {} after {} attempts from device {}",
                                userId, count, deviceFingerprint);
                    }
                });
            }
        }
        return fa;
    }
}
