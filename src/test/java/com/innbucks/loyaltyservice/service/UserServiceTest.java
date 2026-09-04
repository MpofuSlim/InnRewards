package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.PhoneRegistrationRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private LoyaltyUserRepository users;
    private PhoneRegistrationRepository registrations;
    private UserService service;

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String PHONE = "+263771234567";

    @BeforeEach
    void setUp() {
        users = mock(LoyaltyUserRepository.class);
        registrations = mock(PhoneRegistrationRepository.class);
        service = new UserService(users, mock(WalletRepository.class),
                mock(UserServiceClient.class), mock(LoyaltyMetrics.class), registrations);
    }

    /**
     * Marks the phone as having proven ownership. Not calling this leaves the
     * mock's default (false) — i.e. an unregistered phone, which is what every
     * pre-V40 test in this class implicitly assumes.
     */
    private void phoneIsRegistered() {
        when(registrations.existsByPhoneNumberAndRevokedAtIsNull(PHONE)).thenReturn(true);
    }

    @Test
    void requireByPhone_found_returnsUser() {
        LoyaltyUser u = new LoyaltyUser();
        u.setTenantId(TENANT);
        u.setPhoneNumber(PHONE);
        when(users.findByTenantIdAndPhoneNumber(TENANT, PHONE)).thenReturn(Optional.of(u));

        assertThat(service.requireByPhone(TENANT, PHONE)).isSameAs(u);
    }

    @Test
    void requireByPhone_trimsInput_beforeLookup() {
        LoyaltyUser u = new LoyaltyUser();
        when(users.findByTenantIdAndPhoneNumber(TENANT, PHONE)).thenReturn(Optional.of(u));

        assertThat(service.requireByPhone(TENANT, "  " + PHONE + "  ")).isSameAs(u);
    }

    @Test
    void requireByPhone_blank_throwsBadRequest() {
        assertThatThrownBy(() -> service.requireByPhone(TENANT, "   "))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("provide a phone number");
    }

    @Test
    void requireByPhone_null_throwsBadRequest() {
        assertThatThrownBy(() -> service.requireByPhone(TENANT, null))
                .isInstanceOf(LoyaltyException.class);
    }

    @Test
    void requireByPhone_unknownInTenant_throwsNotFound() {
        // Tenant-scoped: a phone that exists only under a different tenant is
        // simply absent from this tenant's lookup -> 404 (no cross-tenant reveal).
        when(users.findByTenantIdAndPhoneNumber(TENANT, PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireByPhone(TENANT, PHONE))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void requireByPhone_localForm_isNormalisedToE164_beforeLookup() {
        // A caller passing the national trunk-0 form (or the bare subscriber
        // number) must resolve to the row stored in canonical +263 E.164 — the
        // service normalises against its cell country (ZW default) first.
        LoyaltyUser u = new LoyaltyUser();
        u.setTenantId(TENANT);
        u.setPhoneNumber(PHONE);
        when(users.findByTenantIdAndPhoneNumber(TENANT, PHONE)).thenReturn(Optional.of(u));

        assertThat(service.requireByPhone(TENANT, "0771234567")).isSameAs(u);
        assertThat(service.requireByPhone(TENANT, "771234567")).isSameAs(u);
    }

    @Test
    void requireByPhone_invalidPhone_throwsBadRequest() {
        assertThatThrownBy(() -> service.requireByPhone(TENANT, "not-a-phone"))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("Invalid phone number");
    }

    // ---- requireSpendable: the spend gate on redeem + outgoing transfer ----
    //
    // Nothing pinned this contract before. The only test that named USER_PENDING
    // was a RedemptionServiceTest mock that fabricated it as a 400, so the real
    // 403 was never asserted anywhere and a status change would have shipped
    // silently. The FE branches on `code` and renders `message`, so both are
    // part of the wire contract, not implementation detail.

    private static LoyaltyUser withStatus(LoyaltyUser.Status status) {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(UUID.randomUUID());
        u.setTenantId(TENANT);
        u.setPhoneNumber(PHONE);
        u.setStatus(status);
        return u;
    }

    @Test
    void requireSpendable_active_passes() {
        service.requireSpendable(withStatus(LoyaltyUser.Status.ACTIVE));
    }

    @Test
    void requireSpendable_pending_is403_USER_PENDING() {
        assertThatThrownBy(() -> service.requireSpendable(withStatus(LoyaltyUser.Status.PENDING)))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("USER_PENDING");
                    assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                });
    }

    @Test
    void requireSpendable_blocked_is403_USER_BLOCKED() {
        assertThatThrownBy(() -> service.requireSpendable(withStatus(LoyaltyUser.Status.BLOCKED)))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("USER_BLOCKED");
                    assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                });
    }

    @Test
    void requireSpendable_inactive_is403_USER_INACTIVE() {
        assertThatThrownBy(() -> service.requireSpendable(withStatus(LoyaltyUser.Status.INACTIVE)))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("USER_INACTIVE");
                    assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                });
    }

    /**
     * Every refusal here is read by a CUSTOMER, so none of them may drift back
     * into developer register. Pins the properties that distinguish customer
     * copy from a log line: addressed to "you", sentence-cased, punctuated.
     * PENDING must also say points keep accruing — "you can't spend" alone
     * reads as though the balance were gone.
     */
    @Test
    void requireSpendable_refusalsAreCustomerFacingProse() {
        for (LoyaltyUser.Status status : new LoyaltyUser.Status[]{
                LoyaltyUser.Status.PENDING, LoyaltyUser.Status.BLOCKED, LoyaltyUser.Status.INACTIVE}) {
            String msg = org.junit.jupiter.api.Assertions.assertThrows(LoyaltyException.class,
                    () -> service.requireSpendable(withStatus(status))).getMessage();
            assertThat(msg).as("%s addresses the customer", status).containsIgnoringCase("your");
            assertThat(msg).as("%s starts sentence-cased", status)
                    .matches("^[A-Z].*");
            assertThat(msg).as("%s is punctuated prose, not a log fragment", status).endsWith(".");
            assertThat(msg).as("%s avoids internal jargon", status)
                    .doesNotContainIgnoringCase("msisdn")
                    .doesNotContainIgnoringCase("null");
        }
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(LoyaltyException.class,
                () -> service.requireSpendable(withStatus(LoyaltyUser.Status.PENDING))).getMessage())
                .as("PENDING reassures that points keep accruing")
                .containsIgnoringCase("earn");
    }

    @Test
    void pendingRefusal_doesNotTellTheCustomerToSignUp() {
        // The regression this pins, reported from production by the customer app.
        //
        // The old copy said "finish signing up to spend your points". That was
        // accurate only while every customer arrived through ticketing's OTP
        // registration, which is what calls the promote webhook
        // (user-service OtpService:363, :378 -> POST /loyalty/internal/users/promote).
        // The customer app now authenticates against a different system and never
        // walks that flow, so the message named a screen its readers have no route
        // to — it asked them to do something impossible.
        //
        // PENDING is cleared by a server-to-server webhook, never by the customer.
        // So this copy must describe a state, never instruct an action, until some
        // caller actually wires promote for the new auth source.
        String msg = org.junit.jupiter.api.Assertions.assertThrows(LoyaltyException.class,
                () -> service.requireSpendable(withStatus(LoyaltyUser.Status.PENDING))).getMessage();

        assertThat(msg)
                .doesNotContainIgnoringCase("sign up")
                .doesNotContainIgnoringCase("signing up")
                .doesNotContainIgnoringCase("signup")
                .doesNotContainIgnoringCase("register")
                .doesNotContainIgnoringCase("registration");
    }

    // ---- V40: registration is a property of the PHONE, status is its cache ----

    @Test
    void requireSpendable_pending_butPhoneIsRegistered_passesAndHeals() {
        // The bug this fixes. A customer who has proven their number can still
        // hold a PENDING projection — minted under a merchant they had not used
        // before, or created before the proof arrived. Refusing them at that
        // till, while the identical customer spends fine at the merchant next
        // door, is the per-projection bug in its purest form.
        phoneIsRegistered();
        LoyaltyUser u = withStatus(LoyaltyUser.Status.PENDING);

        service.requireSpendable(u);

        assertThat(u.getStatus())
                .as("the stale cache is healed in place, so the next read is correct too")
                .isEqualTo(LoyaltyUser.Status.ACTIVE);
    }

    @Test
    void requireSpendable_pending_unregisteredPhone_stillRefuses() {
        // The gate has to keep working. Points accrue to a phone at a till
        // before anyone proves they hold it — a mistyped digit, or somebody
        // else's number entirely — and that balance must stay unspendable.
        assertThatThrownBy(() -> service.requireSpendable(withStatus(LoyaltyUser.Status.PENDING)))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("USER_PENDING"));
    }

    @Test
    void requireSpendable_blocked_isNotRescuedByRegistration() {
        // BLOCKED is a fraud hold. Proving you own the number says nothing about
        // the hold, so registration must not be a way out of one.
        phoneIsRegistered();

        assertThatThrownBy(() -> service.requireSpendable(withStatus(LoyaltyUser.Status.BLOCKED)))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("USER_BLOCKED"));
    }

    @Test
    void isRegistrationPending_readsThePhoneFact_notTheStatus() {
        phoneIsRegistered();
        assertThat(service.isRegistrationPending(withStatus(LoyaltyUser.Status.PENDING)))
                .as("PENDING + registered phone = not actually pending")
                .isFalse();
        assertThat(service.isRegistrationPending(withStatus(LoyaltyUser.Status.ACTIVE))).isFalse();
    }

    @Test
    void isRegistrationPending_unregisteredPendingRow_isPending() {
        assertThat(service.isRegistrationPending(withStatus(LoyaltyUser.Status.PENDING))).isTrue();
    }

    @Test
    void findOrCreatePending_mintsActive_whenThePhoneIsAlreadyRegistered() {
        // A registered customer transacting with a NEW merchant must not be
        // handed a fresh PENDING row: that is how a promoted customer became
        // "unregistered" again simply by shopping somewhere else.
        phoneIsRegistered();
        when(users.findByTenantIdAndPhoneNumber(TENANT, PHONE)).thenReturn(Optional.empty());
        when(users.save(org.mockito.ArgumentMatchers.any(LoyaltyUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        LoyaltyUser created = service.findOrCreatePending(TENANT, PHONE, null);

        assertThat(created.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
    }

    @Test
    void findOrCreatePending_mintsPending_whenThePhoneIsUnknown() {
        when(users.findByTenantIdAndPhoneNumber(TENANT, PHONE)).thenReturn(Optional.empty());
        when(users.save(org.mockito.ArgumentMatchers.any(LoyaltyUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        LoyaltyUser created = service.findOrCreatePending(TENANT, PHONE, null);

        assertThat(created.getStatus()).isEqualTo(LoyaltyUser.Status.PENDING);
    }

    @Test
    void registerPhone_promotesPending_andRevivesAnAgedOutRow() {
        // The age-out recovery. An app customer accrued, waited 90 days for a
        // registration signal that had never been built, and was swept to
        // INACTIVE — a state promoteByPhone deliberately refuses to touch. A
        // proof arriving later is exactly the evidence the sweeper lacked.
        LoyaltyUser pending = withStatus(LoyaltyUser.Status.PENDING);
        LoyaltyUser agedOut = withStatus(LoyaltyUser.Status.INACTIVE);
        agedOut.setStatusReason(LoyaltyUser.StatusReason.PENDING_EXPIRED);
        when(registrations.lockByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(users.findByPhoneNumber(PHONE)).thenReturn(java.util.List.of(pending, agedOut));

        UserService.RegistrationResult result = service.registerPhone(
                PHONE, PhoneRegistration.Source.PARTNER_ASSERTION, "app-1", Instant.now(), "jti-1");

        assertThat(result.newlyRegistered()).isTrue();
        assertThat(result.projectionsPromoted()).isEqualTo(2);
        assertThat(pending.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
        assertThat(agedOut.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
        assertThat(agedOut.getStatusReason()).isNull();
    }

    @Test
    void registerPhone_leavesBlockedAndOperatorDeactivatedRowsAlone() {
        // Two deliberate human/fraud decisions that a customer logging in must
        // not silently reverse.
        LoyaltyUser blocked = withStatus(LoyaltyUser.Status.BLOCKED);
        LoyaltyUser deactivated = withStatus(LoyaltyUser.Status.INACTIVE);
        deactivated.setStatusReason(LoyaltyUser.StatusReason.OPERATOR);
        when(registrations.lockByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(users.findByPhoneNumber(PHONE)).thenReturn(java.util.List.of(blocked, deactivated));

        UserService.RegistrationResult result = service.registerPhone(
                PHONE, PhoneRegistration.Source.PARTNER_KEY, null, null, null);

        assertThat(result.projectionsPromoted()).isZero();
        assertThat(blocked.getStatus()).isEqualTo(LoyaltyUser.Status.BLOCKED);
        assertThat(deactivated.getStatus()).isEqualTo(LoyaltyUser.Status.INACTIVE);
    }

    @Test
    void registerPhone_replayedAssertion_isANoOp() {
        // The endpoint is meant to be called on every login, so replays are
        // normal traffic, not an attack signal. They must not re-notify the
        // customer or re-stamp the row.
        Instant earlier = Instant.parse("2026-09-01T10:00:00Z");
        PhoneRegistration existing = new PhoneRegistration();
        existing.setPhoneNumber(PHONE);
        existing.setLastAssertedAt(Instant.parse("2026-09-02T10:00:00Z"));
        when(registrations.lockByPhoneNumber(PHONE)).thenReturn(Optional.of(existing));

        UserService.RegistrationResult result = service.registerPhone(
                PHONE, PhoneRegistration.Source.PARTNER_ASSERTION, null, earlier, "old-jti");

        assertThat(result.replay()).isTrue();
        assertThat(result.projectionsPromoted()).isZero();
        assertThat(result.newlyRegistered()).isFalse();
        org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).findByPhoneNumber(PHONE);
    }

    @Test
    void registerPhone_newerAssertion_isNotAReplay() {
        PhoneRegistration existing = new PhoneRegistration();
        existing.setPhoneNumber(PHONE);
        existing.setLastAssertedAt(Instant.parse("2026-09-01T10:00:00Z"));
        when(registrations.lockByPhoneNumber(PHONE)).thenReturn(Optional.of(existing));
        when(users.findByPhoneNumber(PHONE)).thenReturn(java.util.List.of());

        UserService.RegistrationResult result = service.registerPhone(
                PHONE, PhoneRegistration.Source.PARTNER_ASSERTION, null,
                Instant.parse("2026-09-02T10:00:00Z"), "new-jti");

        assertThat(result.replay()).isFalse();
        assertThat(existing.getLastAssertionJti()).isEqualTo("new-jti");
    }

    @Test
    void promoteByPhone_stillWorks_andNowRecordsThePhoneLevelFact() {
        // The ticketing OTP webhook's contract is unchanged — it returns the
        // number of rows promoted — but it now writes the phone-level fact too,
        // so a ticketing-verified customer gets ACTIVE projections under future
        // tenants as well.
        LoyaltyUser pending = withStatus(LoyaltyUser.Status.PENDING);
        when(registrations.lockByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(users.findByPhoneNumber(PHONE)).thenReturn(java.util.List.of(pending));

        assertThat(service.promoteByPhone(PHONE)).isEqualTo(1);

        org.mockito.ArgumentCaptor<PhoneRegistration> saved =
                org.mockito.ArgumentCaptor.forClass(PhoneRegistration.class);
        org.mockito.Mockito.verify(registrations).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo(PhoneRegistration.Source.TICKETING_OTP);
        assertThat(saved.getValue().getPhoneNumber()).isEqualTo(PHONE);
    }

    @Test
    void registerPhone_reinstatesARevokedRegistration() {
        // Revocation answers a compromised credential, not a bad customer. The
        // customer proving themselves again through a sound channel is the
        // intended way back.
        PhoneRegistration revoked = new PhoneRegistration();
        revoked.setPhoneNumber(PHONE);
        revoked.setRevokedAt(Instant.parse("2026-09-01T10:00:00Z"));
        revoked.setRevokedReason("key-leak-2026-09");
        when(registrations.lockByPhoneNumber(PHONE)).thenReturn(Optional.of(revoked));
        when(users.findByPhoneNumber(PHONE)).thenReturn(java.util.List.of());

        service.registerPhone(PHONE, PhoneRegistration.Source.PARTNER_ASSERTION, null,
                Instant.now(), "jti-2");

        assertThat(revoked.getRevokedAt()).isNull();
        assertThat(revoked.getRevokedReason()).isNull();
    }

    @Test
    void deactivate_stampsOperator_soRegistrationCannotUndoIt() {
        LoyaltyUser u = withStatus(LoyaltyUser.Status.ACTIVE);
        UUID id = u.getId();
        when(users.findById(id)).thenReturn(Optional.of(u));

        service.deactivate(TENANT, id);

        assertThat(u.getStatus()).isEqualTo(LoyaltyUser.Status.INACTIVE);
        assertThat(u.getStatusReason()).isEqualTo(LoyaltyUser.StatusReason.OPERATOR);
    }
}
