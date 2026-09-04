package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.repository.FraudAttemptRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The velocity auto-block is the only place in this service where a request can
 * make an account unspendable, and nothing in the codebase ever transitions a
 * row back out of {@code BLOCKED}. So the question this class exists to answer
 * is narrow and important: <b>whose</b> account can a request block?
 *
 * <p>Answer: only the authenticated caller's own. Everything else here is a way
 * of trying to break that.
 */
class FraudServiceAutoBlockTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String ATTACKER_PHONE = "+263771111111";
    private static final String VICTIM_PHONE = "+263772222222";
    private static final String DEVICE = "device-fingerprint-1";

    private FraudAttemptRepository fraud;
    private LoyaltyUserRepository users;
    private FraudService service;

    private LoyaltyUser attacker;
    private LoyaltyUser victim;

    @BeforeEach
    void setUp() {
        fraud = mock(FraudAttemptRepository.class);
        users = mock(LoyaltyUserRepository.class);
        LoyaltyMetrics metrics = mock(LoyaltyMetrics.class);

        // Threshold 5 in a 60s window — the committed defaults.
        LoyaltyProperties props = mock(LoyaltyProperties.class);
        when(props.voucher()).thenReturn(
                new LoyaltyProperties.Voucher("s", 365, 5, 60));

        service = new FraudService(fraud, users, props, metrics);

        attacker = user(ATTACKER_PHONE);
        victim = user(VICTIM_PHONE);
        when(users.findById(attacker.getId())).thenReturn(Optional.of(attacker));
        when(users.findById(victim.getId())).thenReturn(Optional.of(victim));

        // Every call in this class is at or over the velocity threshold, so the
        // block branch is always reached; what differs is who it may act on.
        when(fraud.countByDeviceFingerprintAndCreatedAtAfter(any(), any())).thenReturn(5L);
        when(fraud.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static LoyaltyUser user(String phone) {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(UUID.randomUUID());
        u.setTenantId(TENANT);
        u.setPhoneNumber(phone);
        u.setStatus(LoyaltyUser.Status.ACTIVE);
        return u;
    }

    /** Authenticates as a customer, the way a real bearer token or the public surface does. */
    private void authenticateAsCustomer(LoyaltyUser self) {
        var auth = new UsernamePasswordAuthenticationToken(
                "customer", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        auth.setDetails(new CallerDetails(null, null, self.getPhoneNumber(), self.getId()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void authenticateAsShopUser() {
        var auth = new UsernamePasswordAuthenticationToken(
                "cashier", null, List.of(new SimpleGrantedAuthority("ROLE_SHOP_USER")));
        auth.setDetails(new CallerDetails(UUID.randomUUID(), UUID.randomUUID(), null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void recordAttempt(UUID claimedUserId) {
        service.record(TENANT, claimedUserId, UUID.randomUUID(), "VCH-CODE",
                FraudAttempt.Reason.INVALID_CODE, "voucher not found", DEVICE, "10.0.0.1");
    }

    @Test
    @DisplayName("a caller cannot block someone else by naming their id in the body")
    void namingAnotherUsersId_doesNotBlockThem() {
        // THE VULNERABILITY. VoucherService.doRedeem passes req.userId() — a raw,
        // unvalidated body field — straight into record(), on the very first
        // branch, before the voucher is even known to exist. So five malformed
        // redeem calls naming a victim's UUID used to flip that victim to
        // BLOCKED: unspendable, across every tenant, with no code path back.
        authenticateAsCustomer(attacker);

        recordAttempt(victim.getId());

        assertThat(victim.getStatus())
                .as("a victim named only in the request body must never be blocked by it")
                .isEqualTo(LoyaltyUser.Status.ACTIVE);
    }

    @Test
    @DisplayName("a caller brute-forcing codes still blocks their OWN account")
    void ownAccount_isStillBlocked() {
        // The protection this mechanism exists for has to survive the fix,
        // otherwise the fix is just a deletion.
        authenticateAsCustomer(attacker);

        recordAttempt(attacker.getId());

        assertThat(attacker.getStatus()).isEqualTo(LoyaltyUser.Status.BLOCKED);
    }

    @Test
    @DisplayName("blocks the caller even when they name someone else — the id is ignored, not trusted")
    void blockFollowsTheCaller_notTheClaimedId() {
        // Naming a victim must not buy the attacker immunity either: the subject
        // is resolved from the token, so the attempt still counts against them.
        authenticateAsCustomer(attacker);

        recordAttempt(victim.getId());

        assertThat(attacker.getStatus()).isEqualTo(LoyaltyUser.Status.BLOCKED);
        assertThat(victim.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
    }

    @Test
    @DisplayName("a staff-operated till never blocks anyone")
    void staffCaller_blocksNobody() {
        // The velocity signal is keyed by DEVICE. At a till the device is the
        // shop's, and the person presenting bad codes is a customer — so the
        // cashier is not the fraudster, and blocking their loyalty account would
        // let any customer disable a member of staff. The evidence row is still
        // written; only the punitive action is withheld.
        authenticateAsShopUser();

        recordAttempt(victim.getId());

        assertThat(victim.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
        assertThat(attacker.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
    }

    @Test
    @DisplayName("an unauthenticated caller blocks nobody")
    void noAuthentication_blocksNobody() {
        // S2S paths (shop checkout, ticketing) run with no customer principal.
        SecurityContextHolder.clearContext();

        recordAttempt(victim.getId());

        assertThat(victim.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
    }

    @Test
    @DisplayName("a token whose phone doesn't match the resolved row blocks nobody")
    void mismatchedPhoneClaim_blocksNobody() {
        // Belt and braces on a malformed or stale token: the userId claim is
        // only acted on when the row it resolves to is genuinely the caller's,
        // proven by the phone claim the ownership checks elsewhere rely on.
        var auth = new UsernamePasswordAuthenticationToken(
                "customer", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        auth.setDetails(new CallerDetails(null, null, ATTACKER_PHONE, victim.getId()));
        SecurityContextHolder.getContext().setAuthentication(auth);

        recordAttempt(victim.getId());

        assertThat(victim.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
    }

    @Test
    @DisplayName("below the threshold nobody is blocked")
    void belowThreshold_blocksNobody() {
        when(fraud.countByDeviceFingerprintAndCreatedAtAfter(any(), any())).thenReturn(4L);
        authenticateAsCustomer(attacker);

        recordAttempt(attacker.getId());

        assertThat(attacker.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
    }

    @Test
    @DisplayName("the evidence row is still written for every attempt, whoever called")
    void evidenceIsAlwaysRecorded() {
        // Narrowing WHO can be blocked must not narrow what gets recorded — the
        // fraud_attempts table is how an operator sees an attack at all.
        authenticateAsShopUser();

        FraudAttempt saved = service.record(TENANT, victim.getId(), null, "VCH-CODE",
                FraudAttempt.Reason.INVALID_CODE, "voucher not found", DEVICE, "10.0.0.1");

        assertThat(saved).isNotNull();
        assertThat(saved.getUserId())
                .as("the row keeps what the request CLAIMED — it is evidence, not attribution")
                .isEqualTo(victim.getId());
        org.mockito.Mockito.verify(fraud).save(any(FraudAttempt.class));
    }

    @Test
    @DisplayName("an already-blocked caller is not re-blocked")
    void alreadyBlocked_isLeftAlone() {
        attacker.setStatus(LoyaltyUser.Status.BLOCKED);
        authenticateAsCustomer(attacker);

        recordAttempt(attacker.getId());

        assertThat(attacker.getStatus()).isEqualTo(LoyaltyUser.Status.BLOCKED);
    }

    @Test
    @DisplayName("a null device fingerprint never blocks — the velocity signal needs one")
    void nullDevice_blocksNobody() {
        authenticateAsCustomer(attacker);

        service.record(TENANT, attacker.getId(), null, "VCH-CODE",
                FraudAttempt.Reason.INVALID_CODE, "voucher not found", null, "10.0.0.1");

        assertThat(attacker.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
    }

    @Test
    @DisplayName("Instant is not consulted for the subject — the window still bounds the count")
    void velocityWindowIsStillApplied() {
        authenticateAsCustomer(attacker);
        org.mockito.ArgumentCaptor<Instant> since = org.mockito.ArgumentCaptor.forClass(Instant.class);

        recordAttempt(attacker.getId());

        org.mockito.Mockito.verify(fraud)
                .countByDeviceFingerprintAndCreatedAtAfter(org.mockito.ArgumentMatchers.eq(DEVICE), since.capture());
        assertThat(since.getValue()).isBefore(Instant.now());
    }
}
