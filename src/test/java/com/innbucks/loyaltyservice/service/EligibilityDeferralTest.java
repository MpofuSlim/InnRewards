package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.exception.EligibilityCheckDeferred;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.PhoneRegistrationRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deferral wrapper and the spend gate's three modes, without Spring.
 * (End to end through real transactions and Postgres: {@code EligibilityDeferralIT}.)
 */
class EligibilityDeferralTest {

    private static final String PHONE = "+263771234567";

    private OnDemandEligibilityCheck onDemand;
    private UserService registrar;          // the proxied UserService the wrapper registers through
    private EligibilityDeferral deferral;

    // A real UserService for the gate-mode tests.
    private PhoneRegistrationRepository registrations;
    private UserService gate;

    @BeforeEach
    void setUp() {
        onDemand = mock(OnDemandEligibilityCheck.class);
        registrar = mock(UserService.class);
        deferral = new EligibilityDeferral(onDemand, registrar);

        registrations = mock(PhoneRegistrationRepository.class);
        gate = new UserService(mock(LoyaltyUserRepository.class), mock(WalletRepository.class),
                mock(UserServiceClient.class), mock(LoyaltyMetrics.class), registrations, onDemand);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static LoyaltyUser pending() {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(UUID.randomUUID());
        u.setTenantId(UUID.randomUUID());
        u.setPhoneNumber(PHONE);
        u.setStatus(LoyaltyUser.Status.PENDING);
        return u;
    }

    // ---- the wrapper ----

    @Test
    void aDeferral_isAskedOnce_registeredOnce_andReplayedExactlyOnce() {
        when(onDemand.confirmsCustomer(PHONE)).thenReturn(true);
        List<EligibilityDeferral.Mode> modes = new ArrayList<>();

        String result = deferral.run(() -> {
            modes.add(EligibilityDeferral.currentMode());
            if (modes.size() == 1) {
                throw new EligibilityCheckDeferred(PHONE);
            }
            return "spent";
        });

        assertThat(result).isEqualTo("spent");
        assertThat(modes).containsExactly(EligibilityDeferral.Mode.DEFER, EligibilityDeferral.Mode.REPLAY);
        verify(onDemand, times(1)).confirmsCustomer(PHONE);
        verify(registrar, times(1)).registerPhone(PHONE, PhoneRegistration.Source.INNBUCKS_VALIDATE,
                "on-demand-spend", null, null);
        assertThat(EligibilityDeferral.currentMode()).isEqualTo(EligibilityDeferral.Mode.NONE);
    }

    @Test
    void aDirectoryThatDoesNotConfirm_registersNothing_butStillReplaysOnce() {
        when(onDemand.confirmsCustomer(PHONE)).thenReturn(false);
        AtomicInteger runs = new AtomicInteger();

        String result = deferral.run(() -> {
            if (runs.incrementAndGet() == 1) {
                throw new EligibilityCheckDeferred(PHONE);
            }
            return "refused-as-pending-by-the-replay";
        });

        assertThat(result).isEqualTo("refused-as-pending-by-the-replay");
        assertThat(runs).hasValue(2);
        verify(registrar, never()).registerPhone(anyString(), any(), any(), any(), any());
    }

    @Test
    void aReplayThatDefersAgain_isNeverLooped() {
        when(onDemand.confirmsCustomer(PHONE)).thenReturn(true);
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> deferral.run(() -> {
            runs.incrementAndGet();
            throw new EligibilityCheckDeferred(PHONE);
        })).isInstanceOf(EligibilityCheckDeferred.class);

        assertThat(runs).hasValue(2);
        verify(onDemand, times(1)).confirmsCustomer(PHONE);
    }

    @Test
    void anyOtherException_propagatesUntouched_withNoDirectoryCallAndNoReplay() {
        LoyaltyException refusal = LoyaltyException.badRequest("INSUFFICIENT_FUNDS", "no");
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> deferral.run(() -> {
            runs.incrementAndGet();
            throw refusal;
        })).isSameAs(refusal);

        IllegalStateException boom = new IllegalStateException("boom");
        assertThatThrownBy(() -> deferral.run(() -> {
            runs.incrementAndGet();
            throw boom;
        })).isSameAs(boom);

        assertThat(runs).hasValue(2);
        verify(onDemand, never()).confirmsCustomer(anyString());
        assertThat(EligibilityDeferral.currentMode()).isEqualTo(EligibilityDeferral.Mode.NONE);
    }

    @Test
    void aReplayFailure_propagatesUntouched() {
        when(onDemand.confirmsCustomer(PHONE)).thenReturn(true);
        LoyaltyException insufficient = LoyaltyException.badRequest("INSUFFICIENT_FUNDS", "no");
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> deferral.run(() -> {
            if (runs.incrementAndGet() == 1) {
                throw new EligibilityCheckDeferred(PHONE);
            }
            throw insufficient;
        })).isSameAs(insufficient);

        // The registration was still made — it commits on its own (owner-accepted).
        verify(registrar).registerPhone(eq(PHONE), eq(PhoneRegistration.Source.INNBUCKS_VALIDATE),
                eq("on-demand-spend"), any(), any());
    }

    @Test
    void aRegistrationThatCannotBeWritten_isSwallowed_andTheReplayDecides() {
        when(onDemand.confirmsCustomer(PHONE)).thenReturn(true);
        doThrow(new IllegalStateException("db down")).when(registrar)
                .registerPhone(anyString(), any(), any(), any(), any());
        AtomicInteger runs = new AtomicInteger();

        String result = deferral.run(() -> {
            if (runs.incrementAndGet() == 1) {
                throw new EligibilityCheckDeferred(PHONE);
            }
            return "replayed";
        });

        assertThat(result).isEqualTo("replayed");
        assertThat(runs).hasValue(2);
    }

    @Test
    void insideAnOpenTransaction_thereIsNoScope_soTheGateBehavesAsBefore() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        EligibilityDeferral.Mode seen = deferral.run(EligibilityDeferral::currentMode);

        assertThat(seen).isEqualTo(EligibilityDeferral.Mode.NONE);
    }

    @Test
    void aNestedRun_isAPassThrough_soOnlyTheOuterScopeReplays() {
        when(onDemand.confirmsCustomer(PHONE)).thenReturn(false);
        AtomicInteger inner = new AtomicInteger();

        deferral.run(() -> deferral.run(() -> {
            if (inner.incrementAndGet() == 1) {
                throw new EligibilityCheckDeferred(PHONE);
            }
            return null;
        }));

        assertThat(inner).hasValue(2);
        verify(onDemand, times(1)).confirmsCustomer(PHONE);
    }

    // ---- the gate under each mode ----

    @Test
    void noScope_theGateAsksInline_exactlyAsBefore() {
        when(onDemand.isActive()).thenReturn(true);
        when(onDemand.confirmsCustomer(PHONE)).thenReturn(false);

        assertThat(gate.spendabilityOf(pending())).isEqualTo(UserService.Spendability.PENDING_REGISTRATION);
        verify(onDemand).confirmsCustomer(PHONE);
    }

    @Test
    void deferMode_withTheCheckActive_throwsInsteadOfAsking() {
        when(onDemand.isActive()).thenReturn(true);
        LoyaltyUser u = pending();

        // Seen by the wrapper: the first run throws, the replay answers.
        List<UserService.Spendability> answers = new ArrayList<>();
        List<Throwable> thrown = new ArrayList<>();
        deferral.run(() -> {
            try {
                answers.add(gate.spendabilityOf(u));
            } catch (EligibilityCheckDeferred d) {
                thrown.add(d);
                throw d;
            }
            return null;
        });

        assertThat(thrown).singleElement()
                .satisfies(t -> assertThat(((EligibilityCheckDeferred) t).phone()).isEqualTo(PHONE));
        assertThat(thrown.get(0).getMessage()).doesNotContain(PHONE);
        assertThat(answers).containsExactly(UserService.Spendability.PENDING_REGISTRATION);
        // One ask, by the wrapper; the gate never asked under either mode.
        verify(onDemand, times(1)).confirmsCustomer(PHONE);
    }

    @Test
    void deferMode_withTheCheckOff_refusesWithoutDeferring() {
        when(onDemand.isActive()).thenReturn(false);
        AtomicInteger runs = new AtomicInteger();

        UserService.Spendability s = deferral.run(() -> {
            runs.incrementAndGet();
            return gate.spendabilityOf(pending());
        });

        assertThat(s).isEqualTo(UserService.Spendability.PENDING_REGISTRATION);
        assertThat(runs).hasValue(1);
        verify(onDemand, never()).confirmsCustomer(anyString());
    }

    @Test
    void deferMode_aRegisteredPhone_healsWithoutDeferring() {
        when(onDemand.isActive()).thenReturn(true);
        when(registrations.existsByPhoneNumberAndRevokedAtIsNull(PHONE)).thenReturn(true);
        LoyaltyUser u = pending();

        UserService.Spendability s = deferral.run(() -> gate.spendabilityOf(u));

        assertThat(s).isEqualTo(UserService.Spendability.OK);
        assertThat(u.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
        verify(onDemand, never()).confirmsCustomer(anyString());
    }

    @Test
    void replayMode_neverAsksTheDirectory_evenWhenStillUnregistered() {
        when(onDemand.isActive()).thenReturn(true);
        when(onDemand.confirmsCustomer(PHONE)).thenReturn(true);   // e.g. a REVOKED registration stays revoked
        AtomicInteger runs = new AtomicInteger();
        List<UserService.Spendability> replayAnswer = new ArrayList<>();

        deferral.run(() -> {
            if (runs.incrementAndGet() == 1) {
                return gate.spendabilityOf(pending());
            }
            replayAnswer.add(gate.spendabilityOf(pending()));
            return null;
        });

        assertThat(replayAnswer).containsExactly(UserService.Spendability.PENDING_REGISTRATION);
        verify(onDemand, times(1)).confirmsCustomer(PHONE);   // the wrapper's one ask, never the gate's
    }
}
