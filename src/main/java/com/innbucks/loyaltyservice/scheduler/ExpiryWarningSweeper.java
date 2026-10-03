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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

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
 *
 * <p><b>Stamp, commit, THEN send.</b> Each page (up to {@value #BATCH} wallets,
 * then up to {@value #BATCH} vouchers) is stamped in its own short transaction,
 * and only once that commits are the warnings handed to the sweep's own
 * {@code expiryWarningExecutor} — outside any transaction. A page whose stamps
 * fail to commit sends nothing (it is re-found tomorrow), and a committed stamp
 * whose send fails is not retried: at most once, as before. The sweep used to
 * be one transaction that submitted ~1000 sends to the shared per-request
 * executor while its {@code point_lot} / {@code vouchers} updates were still
 * uncommitted — under that pool's caller-runs policy it ran the overflow inline,
 * row locks held. The dedicated executor is caller-runs too, on purpose: a
 * stamped warning must not be dropped, and the scheduler thread holds nothing
 * while it dispatches.
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
    private final TransactionOperations tx;
    private final Executor dispatcher;

    @Autowired
    public ExpiryWarningSweeper(PointLotRepository lots,
                                WalletRepository wallets,
                                VoucherRepository vouchers,
                                MemberActivityNotifier memberNotifier,
                                NotificationGateway voucherNotifier,
                                @Value("${loyalty.expiry-warning.days:7}") long warningDays,
                                PlatformTransactionManager transactionManager,
                                @Qualifier("expiryWarningExecutor") Executor dispatcher) {
        this(lots, wallets, vouchers, memberNotifier, voucherNotifier, warningDays,
                new TransactionTemplate(transactionManager), dispatcher);
    }

    /** Test seam: a transaction runner and an executor the test controls. */
    ExpiryWarningSweeper(PointLotRepository lots,
                         WalletRepository wallets,
                         VoucherRepository vouchers,
                         MemberActivityNotifier memberNotifier,
                         NotificationGateway voucherNotifier,
                         long warningDays,
                         TransactionOperations tx,
                         Executor dispatcher) {
        this.lots = lots;
        this.wallets = wallets;
        this.vouchers = vouchers;
        this.memberNotifier = memberNotifier;
        this.voucherNotifier = voucherNotifier;
        this.warningWindow = Duration.ofDays(warningDays);
        this.tx = tx;
        this.dispatcher = dispatcher;
    }

    /** Deliberately NOT {@code @Transactional}: each page commits on its own,
     *  and the sends happen between commits, never inside one. */
    @Scheduled(cron = "${loyalty.scheduler.expiry-warning-cron:0 0 8 * * *}")
    @SchedulerLock(name = "expiryWarningSweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    public void sweep() {
        Instant now = Instant.now();
        Instant cutoff = now.plus(warningWindow);
        warnPoints(now, cutoff);
        warnVouchers(now, cutoff);
    }

    /** A stamped, reachable wallet: what to send once the stamps commit. */
    private record PointsWarning(String phone, BigDecimal total, LocalDate earliest) {}

    /** A stamped, reachable voucher: what to send once the stamps commit. */
    private record VoucherWarning(Voucher voucher, String phone, LocalDate expiresOn) {}

    private void warnPoints(Instant now, Instant cutoff) {
        int[] scanned = {0};
        List<PointsWarning> toSend;
        try {
            toSend = tx.execute(status -> {
                List<UUID> walletIds = lots.findWalletsWithLotsToWarn(now, cutoff, PageRequest.of(0, BATCH));
                scanned[0] = walletIds.size();
                List<PointsWarning> out = new ArrayList<>();
                for (UUID walletId : walletIds) {
                    try {
                        PointsWarning w = stampWallet(walletId, now, cutoff);
                        if (w != null) {
                            out.add(w);
                        }
                    } catch (RuntimeException e) {
                        log.warn("Points expiry warning failed for wallet {}: {}", walletId, e.toString());
                    }
                }
                return out;
            });
        } catch (RuntimeException e) {
            // Nothing committed, so nothing is sent; tomorrow's run finds the
            // same lots unstamped.
            log.warn("Points expiry-warning page failed to commit; no warnings sent: {}", e.toString());
            return;
        }
        if (scanned[0] == 0 || toSend == null) {
            return;
        }
        int warned = 0;
        for (PointsWarning w : toSend) {
            if (dispatch(() -> memberNotifier.notifyPointsExpiring(w.phone(), w.total(), w.earliest()))) {
                warned++;
            }
        }
        log.info("ExpiryWarningSweeper warned {}/{} wallet(s) with points expiring by {}",
                warned, scanned[0], cutoff);
    }

    /** Stamps the wallet's warnable lots; returns the warning to send, or null
     *  when there is nothing due or no phone to reach. */
    private PointsWarning stampWallet(UUID walletId, Instant now, Instant cutoff) {
        List<PointLot> due = lots.findWarnableLots(walletId, now, cutoff);
        if (due.isEmpty()) {
            return null;
        }
        // Stamp first — the warning is at-most-once even if the send throws.
        due.forEach(l -> l.setExpiryWarnedAt(now));
        lots.saveAll(due);

        String phone = wallets.findById(walletId).map(Wallet::getPhoneNumber).orElse(null);
        if (phone == null || phone.isBlank()) {
            log.debug("Wallet {} has no phone — {} expiring lot(s) marked without warning",
                    walletId, due.size());
            return null;
        }
        BigDecimal total = due.stream()
                .map(PointLot::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate earliest = due.get(0).getExpiresAt().atZone(ZoneOffset.UTC).toLocalDate();
        return new PointsWarning(phone, total, earliest);
    }

    private void warnVouchers(Instant now, Instant cutoff) {
        int[] scanned = {0};
        List<VoucherWarning> toSend;
        try {
            toSend = tx.execute(status -> {
                List<Voucher> due = vouchers.findExpiringForWarning(now, cutoff, PageRequest.of(0, BATCH));
                scanned[0] = due.size();
                List<VoucherWarning> out = new ArrayList<>();
                if (due.isEmpty()) {
                    return out;
                }
                for (Voucher voucher : due) {
                    voucher.setExpiryWarnedAt(now);
                    String phone = voucher.getAssigneePhone();
                    if (phone == null || phone.isBlank()) {
                        continue; // marked, nothing to reach — never rescan it
                    }
                    try {
                        LocalDate expiresOn = voucher.getExpiresAt().atZone(ZoneOffset.UTC).toLocalDate();
                        out.add(new VoucherWarning(voucher, phone, expiresOn));
                    } catch (RuntimeException e) {
                        log.warn("Voucher expiry warning failed id={}: {}", voucher.getId(), e.toString());
                    }
                }
                vouchers.saveAll(due);
                return out;
            });
        } catch (RuntimeException e) {
            log.warn("Voucher expiry-warning page failed to commit; no warnings sent: {}", e.toString());
            return;
        }
        if (scanned[0] == 0 || toSend == null) {
            return;
        }
        int warned = 0;
        for (VoucherWarning w : toSend) {
            if (dispatch(() -> voucherNotifier.warnExpiring(w.voucher(), w.phone(), w.expiresOn()))) {
                warned++;
            }
        }
        log.info("ExpiryWarningSweeper warned {}/{} voucher(s) expiring by {}",
                warned, scanned[0], cutoff);
    }

    /** Hands one send to the sweep's executor (outside any transaction); a
     *  hand-off that throws costs that one warning, never the rest. */
    private boolean dispatch(Runnable send) {
        try {
            dispatcher.execute(send);
            return true;
        } catch (RuntimeException e) {
            log.warn("Expiry warning could not be dispatched: {}", e.toString());
            return false;
        }
    }
}
