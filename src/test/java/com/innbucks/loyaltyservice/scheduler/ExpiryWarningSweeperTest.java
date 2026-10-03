package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.entity.PointLot;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.PointLotRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.testsupport.RecordingTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exactly-once + best-effort contract of the daily expiry-warning sweep:
 * warnable lots/vouchers are stamped on the attempt (even when the recipient
 * is unreachable) so nothing is warned twice or rescanned forever — and the
 * stamps COMMIT before any warning is handed to the sweep's executor, which
 * sends outside any transaction.
 */
class ExpiryWarningSweeperTest {

    private PointLotRepository lots;
    private WalletRepository wallets;
    private VoucherRepository vouchers;
    private MemberActivityNotifier memberNotifier;
    private NotificationGateway voucherNotifier;
    private ExpiryWarningSweeper sweeper;
    private RecordingTransactionManager txManager;
    /** Sends handed to the sweep's executor, run when the test says so. */
    private List<Runnable> dispatched;

    private final UUID walletId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lots = mock(PointLotRepository.class);
        wallets = mock(WalletRepository.class);
        vouchers = mock(VoucherRepository.class);
        memberNotifier = mock(MemberActivityNotifier.class);
        voucherNotifier = mock(NotificationGateway.class);
        txManager = new RecordingTransactionManager();
        dispatched = new ArrayList<>();
        sweeper = new ExpiryWarningSweeper(lots, wallets, vouchers, memberNotifier, voucherNotifier, 7,
                new TransactionTemplate(txManager), dispatched::add);
        when(vouchers.findExpiringForWarning(any(), any(), any())).thenReturn(List.of());
        when(lots.findWalletsWithLotsToWarn(any(), any(), any())).thenReturn(List.of());
    }

    private PointLot lot(BigDecimal remaining, int daysToExpiry) {
        PointLot l = new PointLot();
        l.setWalletId(walletId);
        l.setOriginalAmount(remaining);
        l.setRemainingAmount(remaining);
        l.setEarnedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        l.setExpiresAt(Instant.now().plus(daysToExpiry, ChronoUnit.DAYS));
        return l;
    }

    private Wallet walletWithPhone(String phone) {
        Wallet w = new Wallet();
        w.setPhoneNumber(phone);
        return w;
    }

    @Test
    void pointsInWindow_notifiesTotalAndEarliestDate_andStampsLots() {
        PointLot a = lot(new BigDecimal("30"), 3);
        PointLot b = lot(new BigDecimal("10"), 6);
        when(lots.findWalletsWithLotsToWarn(any(), any(), any())).thenReturn(List.of(walletId));
        when(lots.findWarnableLots(eq(walletId), any(), any())).thenReturn(List.of(a, b));
        when(wallets.findById(walletId)).thenReturn(Optional.of(walletWithPhone("+263771234567")));

        sweepAndDrain();

        verify(memberNotifier).notifyPointsExpiring(eq("+263771234567"),
                eq(new BigDecimal("40")), any(LocalDate.class));
        assertThat(a.getExpiryWarnedAt()).isNotNull();
        assertThat(b.getExpiryWarnedAt()).isNotNull();
        verify(lots).saveAll(List.of(a, b));
    }

    @Test
    void walletWithoutPhone_stampsLotsWithoutNotifying() {
        PointLot a = lot(new BigDecimal("30"), 3);
        when(lots.findWalletsWithLotsToWarn(any(), any(), any())).thenReturn(List.of(walletId));
        when(lots.findWarnableLots(eq(walletId), any(), any())).thenReturn(List.of(a));
        when(wallets.findById(walletId)).thenReturn(Optional.of(walletWithPhone(null)));

        sweepAndDrain();

        verify(memberNotifier, never()).notifyPointsExpiring(any(), any(), any());
        assertThat(a.getExpiryWarnedAt()).isNotNull();
    }

    @Test
    void voucherInWindow_warnsAssigneeAndStamps() {
        Voucher v = new Voucher();
        v.setAssigneePhone("+263779999999");
        v.setExpiresAt(Instant.now().plus(2, ChronoUnit.DAYS));
        when(vouchers.findExpiringForWarning(any(), any(), any())).thenReturn(List.of(v));

        sweepAndDrain();

        verify(voucherNotifier).warnExpiring(eq(v), eq("+263779999999"), any(LocalDate.class));
        assertThat(v.getExpiryWarnedAt()).isNotNull();
        verify(vouchers).saveAll(List.of(v));
    }

    @Test
    void voucherWithoutPhone_stampedSilently() {
        Voucher v = new Voucher();
        v.setAssigneePhone(null);
        v.setExpiresAt(Instant.now().plus(2, ChronoUnit.DAYS));
        when(vouchers.findExpiringForWarning(any(), any(), any())).thenReturn(List.of(v));

        sweepAndDrain();

        verify(voucherNotifier, never()).warnExpiring(any(), any(), any());
        assertThat(v.getExpiryWarnedAt()).isNotNull();
        verify(vouchers).saveAll(List.of(v));
    }

    @Test
    void nothingInWindow_isQuiet() {
        sweepAndDrain();
        verify(memberNotifier, never()).notifyPointsExpiring(any(), any(), any());
        verify(voucherNotifier, never()).warnExpiring(any(), any(), any());
    }

    private void sweepAndDrain() {
        sweeper.sweep();
        dispatched.forEach(Runnable::run);
    }

    @Test
    void pointStamps_commitBeforeTheWarningIsDispatched_andTheSendRunsOutsideAnyTransaction() {
        PointLot a = lot(new BigDecimal("30"), 3);
        when(lots.findWalletsWithLotsToWarn(any(), any(), any())).thenReturn(List.of(walletId));
        when(lots.findWarnableLots(eq(walletId), any(), any())).thenReturn(List.of(a));
        when(wallets.findById(walletId)).thenReturn(Optional.of(walletWithPhone("+263771234567")));
        // The stamp is written INSIDE the page transaction...
        when(lots.saveAll(any())).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return inv.getArgument(0);
        });
        // ...and the send runs with no transaction, after the stamp committed.
        doAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(txManager.commits.get()).isGreaterThanOrEqualTo(1);
            return null;
        }).when(memberNotifier).notifyPointsExpiring(any(), any(), any());

        sweeper.sweep();

        // Handed to the executor, not sent on the sweep's own call stack.
        verify(memberNotifier, never()).notifyPointsExpiring(any(), any(), any());
        assertThat(dispatched).hasSize(1);
        assertThat(txManager.commits.get()).isEqualTo(2); // points page + voucher page

        dispatched.forEach(Runnable::run);
        verify(memberNotifier).notifyPointsExpiring(eq("+263771234567"), eq(new BigDecimal("30")),
                any(LocalDate.class));
    }

    @Test
    void voucherStamps_commitBeforeTheWarningIsDispatched_andTheSendRunsOutsideAnyTransaction() {
        Voucher v = new Voucher();
        v.setAssigneePhone("+263779999999");
        v.setExpiresAt(Instant.now().plus(2, ChronoUnit.DAYS));
        when(vouchers.findExpiringForWarning(any(), any(), any())).thenReturn(List.of(v));
        doAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(txManager.commits.get()).isEqualTo(2);
            return null;
        }).when(voucherNotifier).warnExpiring(any(), any(), any());

        sweeper.sweep();
        verify(voucherNotifier, never()).warnExpiring(any(), any(), any());

        dispatched.forEach(Runnable::run);
        verify(voucherNotifier).warnExpiring(eq(v), eq("+263779999999"), any(LocalDate.class));
    }

    @Test
    void aPageWhoseStampsFailToCommit_sendsNothing() {
        PointLot a = lot(new BigDecimal("30"), 3);
        when(lots.findWalletsWithLotsToWarn(any(), any(), any())).thenReturn(List.of(walletId));
        when(lots.findWarnableLots(eq(walletId), any(), any())).thenReturn(List.of(a));
        when(wallets.findById(walletId)).thenReturn(Optional.of(walletWithPhone("+263771234567")));
        txManager.failNextCommit();

        sweepAndDrain();

        // Not stamped (rolled back) → not warned; tomorrow's run finds it again.
        verify(memberNotifier, never()).notifyPointsExpiring(any(), any(), any());
        assertThat(dispatched).isEmpty();
    }

    /**
     * The two sends the sweep makes must stay SYNCHRONOUS: the sweep runs them
     * on its own caller-runs executor because a stamped warning is never
     * retried. An {@code @Async} on either would re-route it to the shared
     * drop-and-count notification executor, where a busy pool loses it.
     */
    @Test
    void theSweepsSends_areNotAsync_soTheyNeverReachTheDropAndCountExecutor() throws Exception {
        assertThat(MemberActivityNotifier.class.getMethod("notifyPointsExpiring",
                String.class, BigDecimal.class, LocalDate.class).isAnnotationPresent(Async.class)).isFalse();
        assertThat(NotificationGateway.class.getMethod("warnExpiring",
                Voucher.class, String.class, LocalDate.class).isAnnotationPresent(Async.class)).isFalse();
        // Everything else on those classes still is.
        assertThat(MemberActivityNotifier.class.getMethod("notifyPointsEarned",
                String.class, BigDecimal.class, BigDecimal.class).isAnnotationPresent(Async.class)).isTrue();
        assertThat(NotificationGateway.class.getMethod("deliver",
                Voucher.class, String.class).isAnnotationPresent(Async.class)).isTrue();
    }
}
