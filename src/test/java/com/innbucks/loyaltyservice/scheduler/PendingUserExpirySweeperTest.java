package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweeper has two arms that pull in opposite directions, so what it does
 * NOT touch matters as much as what it does.
 *
 * <p>Pure JUnit with mocked repositories — the queries themselves are exercised
 * against real Postgres by the integration suite; what is pinned here is which
 * query each arm calls and what it writes, which is where the reasoning lives.
 */
class PendingUserExpirySweeperTest {

    private LoyaltyUserRepository users;
    private LoyaltyMetrics metrics;
    private PendingUserExpirySweeper sweeper;

    @BeforeEach
    void setUp() {
        users = mock(LoyaltyUserRepository.class);
        metrics = mock(LoyaltyMetrics.class);
        sweeper = new PendingUserExpirySweeper(users, metrics, 90);
        when(users.findPendingButRegistered()).thenReturn(List.of());
        when(users.findStaleUnregistered(any())).thenReturn(List.of());
    }

    private static LoyaltyUser pending() {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(UUID.randomUUID());
        u.setPhoneNumber("+263771234567");
        u.setStatus(LoyaltyUser.Status.PENDING);
        return u;
    }

    @Test
    @DisplayName("heals a PENDING row whose phone is registered")
    void healArm_promotesRegisteredPendingRows() {
        LoyaltyUser stale = pending();
        when(users.findPendingButRegistered()).thenReturn(List.of(stale));

        sweeper.sweep();

        assertThat(stale.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
        assertThat(stale.getStatusReason()).isNull();
        verify(metrics).incPendingPromoted(1);
    }

    @Test
    @DisplayName("ages out an unregistered PENDING row and stamps it PENDING_EXPIRED")
    void ageOutArm_stampsTheReason() {
        LoyaltyUser stale = pending();
        when(users.findStaleUnregistered(any())).thenReturn(List.of(stale));

        sweeper.sweep();

        assertThat(stale.getStatus()).isEqualTo(LoyaltyUser.Status.INACTIVE);
        assertThat(stale.getStatusReason())
                .as("the reason is what makes this recoverable by a later proof")
                .isEqualTo(LoyaltyUser.StatusReason.PENDING_EXPIRED);
    }

    @Test
    @DisplayName("the age-out query is the registration-aware one, not the old status+age query")
    void ageOutArm_neverUsesTheRegistrationBlindQuery() {
        // The regression this pins. findByStatusAndCreatedAtBefore selects on
        // status and age alone, so it would sweep a customer who HAD proven
        // their number but whose projection had not caught up — into INACTIVE,
        // a state the promote webhook deliberately refuses to recover. The
        // NOT EXISTS in findStaleUnregistered is the whole difference.
        sweeper.sweep();

        verify(users).findStaleUnregistered(any());
        verify(users, org.mockito.Mockito.never())
                .findByStatusAndCreatedAtBefore(any(), any());
    }

    @Test
    @DisplayName("the cutoff is TTL days back from now")
    void ageOutArm_usesTheConfiguredTtl() {
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);

        sweeper.sweep();

        verify(users).findStaleUnregistered(cutoff.capture());
        Instant expected = Instant.now().minus(90, ChronoUnit.DAYS);
        assertThat(java.time.Duration.between(cutoff.getValue(), expected).abs())
                .isLessThan(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a quiet sweep writes nothing and counts nothing")
    void nothingToDo_isSilent() {
        sweeper.sweep();

        verify(metrics, org.mockito.Mockito.never()).incPendingPromoted(org.mockito.ArgumentMatchers.anyInt());
    }
}
