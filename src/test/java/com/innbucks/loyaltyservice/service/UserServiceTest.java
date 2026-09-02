package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private LoyaltyUserRepository users;
    private UserService service;

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String PHONE = "+263771234567";

    @BeforeEach
    void setUp() {
        users = mock(LoyaltyUserRepository.class);
        service = new UserService(users, mock(WalletRepository.class),
                mock(UserServiceClient.class), mock(LoyaltyMetrics.class));
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
}
