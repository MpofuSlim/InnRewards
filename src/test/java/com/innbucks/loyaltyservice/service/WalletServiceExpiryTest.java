package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.PointLot;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.repository.PointLotRepository;
import com.innbucks.loyaltyservice.repository.PointsLedgerRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Points-expiry configuration contract (V31): the default is that points NEVER
 * expire, and expiry is still available per-cell behind a positive
 * {@code loyalty.points.expiry-days}.
 *
 * <p>Pure JUnit + Mockito so it runs without Docker; the end-to-end
 * earn/redeem/sweep behaviour lives in {@code PointExpiryIT}.
 */
class WalletServiceExpiryTest {

    /** Build a WalletService with all collaborators mocked, at a given expiry setting. */
    private static class Fixture {
        final WalletRepository wallets = mock(WalletRepository.class);
        final PointsLedgerRepository ledger = mock(PointsLedgerRepository.class);
        final PointLotRepository lots = mock(PointLotRepository.class);
        final LoyaltyMetrics metrics = mock(LoyaltyMetrics.class);
        final WalletService service;
        final Wallet wallet = new Wallet();

        Fixture(int expiryDays) {
            service = new WalletService(wallets, ledger, lots, metrics, expiryDays);
            wallet.setId(UUID.randomUUID());
            wallet.setBalance(BigDecimal.ZERO);
            when(wallets.lockById(wallet.getId())).thenReturn(Optional.of(wallet));
            // No lots due for expiry and none to consume unless a test says so.
            when(lots.findDueForExpiry(any(), any())).thenReturn(List.of());
            when(lots.findLiveForConsumption(any(), any())).thenReturn(List.of());
        }

        /** The lot opened by the credit under test. */
        PointLot creditAndCaptureLot(String amount) {
            service.apply(wallet.getId(), new BigDecimal(amount), UUID.randomUUID(), "earn", null);
            ArgumentCaptor<PointLot> lot = ArgumentCaptor.forClass(PointLot.class);
            verify(lots).save(lot.capture());
            return lot.getValue();
        }
    }

    @Test
    void defaultConfiguration_opensALotThatNeverExpires() {
        // 0 is the shipped default (application.yaml) — points do not expire.
        PointLot lot = new Fixture(0).creditAndCaptureLot("100");

        assertThat(lot.getExpiresAt())
                .as("a NULL expiry is what makes the lot invisible to every expiry query")
                .isNull();
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("100");
    }

    @Test
    void negativeConfiguration_isAlsoTreatedAsNeverExpires() {
        // Guards the boundary: the check is expiryDays > 0, not != 0, so a
        // negative value can't mint a lot that expired before it was earned.
        assertThat(new Fixture(-1).creditAndCaptureLot("50").getExpiresAt()).isNull();
    }

    @Test
    void positiveConfiguration_stillSetsAnExpiry_soTheMechanismIsOnlyOffNotGone() {
        Instant before = Instant.now();

        PointLot lot = new Fixture(30).creditAndCaptureLot("100");

        assertThat(lot.getExpiresAt()).isNotNull();
        assertThat(lot.getExpiresAt())
                .as("30 days out from the credit")
                .isBetween(before.plus(30, ChronoUnit.DAYS).minusSeconds(60),
                        Instant.now().plus(30, ChronoUnit.DAYS).plusSeconds(60));
    }

    @Test
    void nonExpiringPointsAreStillSpendable() {
        Fixture f = new Fixture(0);
        f.wallet.setBalance(new BigDecimal("100"));
        PointLot open = new PointLot();
        open.setId(UUID.randomUUID());
        open.setWalletId(f.wallet.getId());
        open.setRemainingAmount(new BigDecimal("100"));
        open.setExpiresAt(null);
        when(f.lots.findLiveForConsumption(any(), any())).thenReturn(List.of(open));

        BigDecimal balance = f.service.apply(f.wallet.getId(), new BigDecimal("-40"),
                UUID.randomUUID(), "redeem", null);

        // The repository query has to return NULL-expiry lots or the customer's
        // entire balance would be unspendable; this pins the burn side of that.
        assertThat(balance).isEqualByComparingTo("60");
        assertThat(open.getRemainingAmount()).isEqualByComparingTo("60");
    }

    @Test
    void aWalletOfNonExpiringPointsReleasesNoBreakage() {
        Fixture f = new Fixture(0);
        f.wallet.setBalance(new BigDecimal("100"));
        // findDueForExpiry never returns a NULL-expiry lot (NULL <= now is
        // UNKNOWN), so the sweep has nothing to release and the balance stands.
        f.service.expireDueLots(f.wallet.getId());

        assertThat(f.wallet.getBalance()).isEqualByComparingTo("100");
        verify(f.metrics, org.mockito.Mockito.never()).addPointsExpired(any());
        verify(f.ledger, org.mockito.Mockito.never()).saveAll(anyList());
    }
}
