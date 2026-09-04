package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /loyalty/users/me} — the self-lookup that makes the authenticated
 * spend endpoints reachable from a customer app.
 *
 * <p><b>Why it exists.</b> {@code POST /loyalty/transfer} requires a
 * {@code fromUserId} and {@code POST /loyalty/redeem} a {@code userId}, both
 * loyalty account UUIDs, and every tenant-scoped call requires an
 * {@code X-Tenant-Id}. An app that authenticates with a phone-scoped session
 * holds neither. Without this endpoint the authenticated surface is documented
 * but not actually callable.
 *
 * <p><b>Why it needs no ownership check.</b> The phone comes from the token and
 * there is no path variable, body or filter — so unlike every other
 * customer-reachable endpoint there is no caller-supplied input to bind against.
 * The lookup can only ever be for the caller. That is what
 * {@link #lookupUsesTheTokenPhoneOnly()} pins.
 */
class MeAccountsTest {

    private static final String PHONE = "+263777224008";

    private LoyaltyUserRepository users;
    private MeController controller;

    @BeforeEach
    void setUp() {
        users = mock(LoyaltyUserRepository.class);
        controller = new MeController(users, mock(VoucherRepository.class), mock(WalletRepository.class));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String phone) {
        var auth = new UsernamePasswordAuthenticationToken(
                "customer@test.local", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        auth.setDetails(new CallerDetails(null, null, phone, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static LoyaltyUser projection(UUID id, UUID tenantId, UUID merchantId,
                                          LoyaltyUser.Status status) {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(id);
        u.setTenantId(tenantId);
        u.setMerchantId(merchantId);
        u.setPhoneNumber(PHONE);
        u.setStatus(status);
        return u;
    }

    @Test
    @DisplayName("returns the userId and tenantId the spend endpoints need")
    void returnsIdsForSpending() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(users.findByPhoneNumber(PHONE))
                .thenReturn(List.of(projection(userId, tenantId, merchantId, LoyaltyUser.Status.ACTIVE)));
        authenticateAs(PHONE);

        var body = controller.accounts().getBody().getData();

        assertThat(body.phoneNumber()).isEqualTo(PHONE);
        assertThat(body.accounts()).singleElement().satisfies(a -> {
            // These two fields ARE the point of the endpoint: fromUserId /
            // userId on transfer and redeem, and the X-Tenant-Id header.
            assertThat(a.userId()).isEqualTo(userId);
            assertThat(a.tenantId()).isEqualTo(tenantId);
            assertThat(a.merchantId()).isEqualTo(merchantId);
            assertThat(a.status()).isEqualTo("ACTIVE");
        });
    }

    @Test
    @DisplayName("SECURITY: the lookup is keyed by the TOKEN's phone, with no caller-supplied input")
    void lookupUsesTheTokenPhoneOnly() {
        // There is no parameter to point at another customer — the endpoint
        // takes none. This asserts the repository is queried with the token's
        // phone and nothing else, which is the whole reason it can safely be
        // isAuthenticated() rather than carrying an ownership check.
        when(users.findByPhoneNumber(anyString())).thenReturn(List.of());
        authenticateAs(PHONE);

        controller.accounts();

        verify(users).findByPhoneNumber(PHONE);
        verify(users, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("returns every tenant's projection — a customer's accounts are not one per app")
    void returnsAllTenants() {
        // Wallets are global per phone but projections are per tenant, so a
        // customer who has shopped at two merchants under different tenants has
        // two accounts and the app must be able to pick the right one.
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of(
                projection(UUID.randomUUID(), UUID.randomUUID(), null, LoyaltyUser.Status.ACTIVE),
                projection(UUID.randomUUID(), UUID.randomUUID(), null, LoyaltyUser.Status.ACTIVE)));
        authenticateAs(PHONE);

        assertThat(controller.accounts().getBody().getData().accounts()).hasSize(2);
    }

    @Test
    @DisplayName("a PENDING account is REPORTED, not hidden")
    void pendingIsVisible() {
        // Hiding it would leave the app unable to explain why a redeem was
        // refused. The status is surfaced so the client can say "you can earn
        // but not spend yet" instead of showing nothing.
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of(
                projection(UUID.randomUUID(), UUID.randomUUID(), null, LoyaltyUser.Status.PENDING)));
        authenticateAs(PHONE);

        assertThat(controller.accounts().getBody().getData().accounts())
                .singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo("PENDING"));
    }

    @Test
    @DisplayName("no projections yet is an empty list and a 200, not an error")
    void noProjectionsIsEmptyNotError() {
        // A customer who has proved their phone but never transacted has no
        // projection. That is a normal state, not a failure.
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of());
        authenticateAs(PHONE);

        var response = controller.accounts();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData().accounts()).isEmpty();
        assertThat(response.getBody().getData().phoneNumber()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("a token with no phone claim is a 400, and never queries")
    void noPhoneClaimIs400() {
        // A staff token carries no phoneNumber. Rather than returning someone
        // else's rows or an empty list that reads as "you have no accounts",
        // say plainly that this endpoint is not for that caller.
        authenticateAs(null);

        assertThatThrownBy(() -> controller.accounts())
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    assertThat(((LoyaltyException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((LoyaltyException) e).getCode()).isEqualTo("NO_PHONE_CLAIM");
                });
        verify(users, never()).findByPhoneNumber(anyString());
    }

    @Test
    @DisplayName("a blank phone claim is treated the same as none")
    void blankPhoneClaimIs400() {
        authenticateAs("   ");

        assertThatThrownBy(() -> controller.accounts())
                .isInstanceOf(LoyaltyException.class);
        verify(users, never()).findByPhoneNumber(anyString());
    }

    @Test
    @DisplayName("a null status does not blow up the mapping")
    void nullStatusIsTolerated() {
        LoyaltyUser u = projection(UUID.randomUUID(), UUID.randomUUID(), null, null);
        u.setStatus(null);
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of(u));
        authenticateAs(PHONE);

        assertThat(controller.accounts().getBody().getData().accounts())
                .singleElement()
                .satisfies(a -> assertThat(a.status()).isNull());
    }
}
