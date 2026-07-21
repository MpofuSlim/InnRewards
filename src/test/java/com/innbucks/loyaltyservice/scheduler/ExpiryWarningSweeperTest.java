package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.entity.PointLot;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.PointLotRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exactly-once + best-effort contract of the daily expiry-warning sweep:
 * warnable lots/vouchers are stamped on the attempt (even when the recipient
 * is unreachable) so nothing is warned twice or rescanned forever.
 */
class ExpiryWarningSweeperTest {

    private PointLotRepository lots;
    private WalletRepository wallets;
    private VoucherRepository vouchers;
    private MemberActivityNotifier memberNotifier;
    private NotificationGateway voucherNotifier;
    private ExpiryWarningSweeper sweeper;

    private final UUID walletId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lots = mock(PointLotRepository.class);
        wallets = mock(WalletRepository.class);
        vouchers = mock(VoucherRepository.class);
        memberNotifier = mock(MemberActivityNotifier.class);
        voucherNotifier = mock(NotificationGateway.class);
        sweeper = new ExpiryWarningSweeper(lots, wallets, vouchers, memberNotifier, voucherNotifier, 7);
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

        sweeper.sweep();

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

        sweeper.sweep();

        verify(memberNotifier, never()).notifyPointsExpiring(any(), any(), any());
        assertThat(a.getExpiryWarnedAt()).isNotNull();
    }

    @Test
    void voucherInWindow_warnsAssigneeAndStamps() {
        Voucher v = new Voucher();
        v.setAssigneePhone("+263779999999");
        v.setExpiresAt(Instant.now().plus(2, ChronoUnit.DAYS));
        when(vouchers.findExpiringForWarning(any(), any(), any())).thenReturn(List.of(v));

        sweeper.sweep();

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

        sweeper.sweep();

        verify(voucherNotifier, never()).warnExpiring(any(), any(), any());
        assertThat(v.getExpiryWarnedAt()).isNotNull();
        verify(vouchers).saveAll(List.of(v));
    }

    @Test
    void nothingInWindow_isQuiet() {
        sweeper.sweep();
        verify(memberNotifier, never()).notifyPointsExpiring(any(), any(), any());
        verify(voucherNotifier, never()).warnExpiring(any(), any(), any());
    }
}
