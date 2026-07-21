package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.entity.PointLot;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.PointLotRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Daily retention nudges for value that is about to lapse: point lots and
 * unredeemed vouchers entering the warning window (default 7 days before
 * expiry) trigger ONE "expires soon" notification each — points via
 * {@link MemberActivityNotifier} (SMS-primary), vouchers via
 * {@link NotificationGateway} (WhatsApp-primary, the voucher convention).
 *
 * <p>Exactly-once: {@code expiry_warned_at} (V28) is stamped on the attempt —
 * success, failure, or unreachable recipient — so the sweep never re-warns
 * and warned rows drop out of the scan (partial indexes keep it cheap).
 * Best-effort throughout: a notification failure never affects the points or
 * voucher themselves, and one bad wallet/voucher never blocks the batch.
 */
@Component
public class ExpiryWarningSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpiryWarningSweeper.class);
    private static final int BATCH = 500;

    private final PointLotRepository lots;
    private final WalletRepository wallets;
    private final VoucherRepository vouchers;
    private final MemberActivityNotifier memberNotifier;
    private final NotificationGateway voucherNotifier;
    private final Duration warningWindow;

    public ExpiryWarningSweeper(PointLotRepository lots,
                                WalletRepository wallets,
                                VoucherRepository vouchers,
                                MemberActivityNotifier memberNotifier,
                                NotificationGateway voucherNotifier,
                                @Value("${loyalty.expiry-warning.days:7}") long warningDays) {
        this.lots = lots;
        this.wallets = wallets;
        this.vouchers = vouchers;
        this.memberNotifier = memberNotifier;
        this.voucherNotifier = voucherNotifier;
        this.warningWindow = Duration.ofDays(warningDays);
    }

    @Scheduled(cron = "${loyalty.scheduler.expiry-warning-cron:0 0 8 * * *}")
    @SchedulerLock(name = "expiryWarningSweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    @Transactional
    public void sweep() {
        Instant now = Instant.now();
        Instant cutoff = now.plus(warningWindow);
        warnPoints(now, cutoff);
        warnVouchers(now, cutoff);
    }

    private void warnPoints(Instant now, Instant cutoff) {
        List<UUID> walletIds = lots.findWalletsWithLotsToWarn(now, cutoff, PageRequest.of(0, BATCH));
        if (walletIds.isEmpty()) {
            return;
        }
        int warned = 0;
        for (UUID walletId : walletIds) {
            try {
                if (warnWallet(walletId, now, cutoff)) {
                    warned++;
                }
            } catch (RuntimeException e) {
                log.warn("Points expiry warning failed for wallet {}: {}", walletId, e.toString());
            }
        }
        log.info("ExpiryWarningSweeper warned {}/{} wallet(s) with points expiring by {}",
                warned, walletIds.size(), cutoff);
    }

    /** @return true when a notification was actually dispatched. */
    private boolean warnWallet(UUID walletId, Instant now, Instant cutoff) {
        List<PointLot> due = lots.findWarnableLots(walletId, now, cutoff);
        if (due.isEmpty()) {
            return false;
        }
        // Stamp first — the warning is at-most-once even if the send throws.
        due.forEach(l -> l.setExpiryWarnedAt(now));
        lots.saveAll(due);

        String phone = wallets.findById(walletId).map(Wallet::getPhoneNumber).orElse(null);
        if (phone == null || phone.isBlank()) {
            log.debug("Wallet {} has no phone — {} expiring lot(s) marked without warning",
                    walletId, due.size());
            return false;
        }
        BigDecimal total = due.stream()
                .map(PointLot::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate earliest = due.get(0).getExpiresAt().atZone(ZoneOffset.UTC).toLocalDate();
        memberNotifier.notifyPointsExpiring(phone, total, earliest);
        return true;
    }

    private void warnVouchers(Instant now, Instant cutoff) {
        List<Voucher> due = vouchers.findExpiringForWarning(now, cutoff, PageRequest.of(0, BATCH));
        if (due.isEmpty()) {
            return;
        }
        int warned = 0;
        for (Voucher voucher : due) {
            voucher.setExpiryWarnedAt(now);
            String phone = voucher.getAssigneePhone();
            if (phone == null || phone.isBlank()) {
                continue; // marked, nothing to reach — never rescan it
            }
            try {
                LocalDate expiresOn = voucher.getExpiresAt().atZone(ZoneOffset.UTC).toLocalDate();
                voucherNotifier.warnExpiring(voucher, phone, expiresOn);
                warned++;
            } catch (RuntimeException e) {
                log.warn("Voucher expiry warning failed id={}: {}", voucher.getId(), e.toString());
            }
        }
        vouchers.saveAll(due);
        log.info("ExpiryWarningSweeper warned {}/{} voucher(s) expiring by {}",
                warned, due.size(), cutoff);
    }
}
