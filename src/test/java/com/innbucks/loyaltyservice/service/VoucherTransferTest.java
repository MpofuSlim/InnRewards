package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherBatchRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single-hop rule for voucher p2p transfer.
 *
 * <p>Pure JUnit + Mockito per the no-Docker convention — the rule under test is
 * a decision in {@code VoucherService}, not SQL.
 *
 * <p>The load-bearing case is
 * {@link #transferringAnAlreadyTransferredVoucher_isRefused()}: a voucher is a
 * merchant liability at a frozen value, and if it could circulate freely it
 * would be a bearer instrument the merchant is billed for but can no longer
 * trace. One hop covers "I can't use this, take it" without that.
 */
class VoucherTransferTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final String HOLDER_PHONE = "+263771111111";
    private static final String RECIPIENT_PHONE = "+263772222222";

    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier =
            mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class);
    private final UserService userService = mock(UserService.class);
    private VoucherService service;

    @BeforeEach
    void setUp() {
        LoyaltyProperties props = mock(LoyaltyProperties.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(props.voucher().secret()).thenReturn("test-voucher-secret");

        service = new VoucherService(
                vouchers,
                mock(VoucherBatchRepository.class),
                mock(VoucherRedemptionRepository.class),
                mock(VoucherTemplateService.class),
                mock(MerchantService.class),
                mock(LoyaltyUserRepository.class),
                userService,
                mock(NotificationGateway.class),
                mock(FraudService.class),
                new LoyaltyMetrics(new SimpleMeterRegistry()),
                memberNotifier,
                props);

        authenticateAs(HOLDER_PHONE, "ROLE_CUSTOMER");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- the single-hop rule ----

    @Test
    void transferMovesTheVoucherAndStampsWhereItCameFrom() {
        Voucher v = liveVoucher();
        UUID originalHolderId = v.getAssignedUserId();
        stubLookup(v);
        stubRecipient();

        Dtos.VoucherResponse resp = service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, RECIPIENT_PHONE, null));

        // New holder owns it...
        assertThat(v.getAssigneePhone()).isEqualTo(RECIPIENT_PHONE);
        assertThat(resp.assigneePhone()).isEqualTo(RECIPIENT_PHONE);
        // ...and the old holder is still recoverable, which is the only reason
        // the transferred_from_* columns exist — the reassignment above
        // overwrites the sole record of who held it before.
        assertThat(v.getTransferredFromPhone()).isEqualTo(HOLDER_PHONE);
        assertThat(v.getTransferredFromUserId()).isEqualTo(originalHolderId);
        assertThat(v.getTransferredAt()).isNotNull();
    }

    @Test
    void transferringAnAlreadyTransferredVoucher_isRefused() {
        // THE rule. transferredAt being non-null is the entire test for
        // "this has had its one hop".
        Voucher v = liveVoucher();
        v.setTransferredAt(Instant.now().minus(1, ChronoUnit.DAYS));
        v.setTransferredFromPhone("+263779999999");
        stubLookup(v);

        assertThatThrownBy(() -> service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, RECIPIENT_PHONE, null)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("already been transferred once");

        // Refused before the recipient is even resolved — a rejected transfer
        // must not auto-enrol a PENDING user as a side effect.
        verify(userService, never()).findOrCreatePending(any(), anyString(), any());
        assertThat(v.getAssigneePhone())
                .as("a refused transfer must not move the voucher")
                .isEqualTo(HOLDER_PHONE);
    }

    // ---- which vouchers may move ----

    @Test
    void aRedeemedVoucher_cannotBeTransferred() {
        Voucher v = liveVoucher();
        v.setStatus(Voucher.Status.REDEEMED);
        stubLookup(v);

        assertThatThrownBy(() -> service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, RECIPIENT_PHONE, null)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("Only an unused voucher");
    }

    @Test
    void aPartiallyUsedVoucher_cannotBeTransferred() {
        // Deliberately grouped with the terminal states rather than the live
        // ones: the original holder already consumed part of the value, so
        // handing over the remainder splits one voucher across two people and
        // makes the redemption trail ambiguous about who got what.
        Voucher v = liveVoucher();
        v.setStatus(Voucher.Status.PARTIALLY_USED);
        stubLookup(v);

        assertThatThrownBy(() -> service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, RECIPIENT_PHONE, null)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("Only an unused voucher");
    }

    @Test
    void anExpiredVoucher_cannotBeTransferred() {
        Voucher v = liveVoucher();
        v.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        stubLookup(v);

        assertThatThrownBy(() -> service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, RECIPIENT_PHONE, null)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("expired");
    }

    // ---- who may move it, and to whom ----

    @Test
    void aCallerWhoIsNotTheHolder_isRefused() {
        authenticateAs("+263778888888", "ROLE_CUSTOMER");
        Voucher v = liveVoucher();
        stubLookup(v);

        assertThatThrownBy(() -> service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, RECIPIENT_PHONE, null)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("your own vouchers");
    }

    @Test
    void transferringToYourself_isRefusedEvenViaASiblingProjection() {
        // A customer has one LoyaltyUser per tenant, so an id comparison alone
        // would let someone "transfer" to their own other projection and burn
        // the single hop for nothing. The phone is the identity that matters.
        Voucher v = liveVoucher();
        stubLookup(v);
        LoyaltyUser sameCustomerDifferentProjection = new LoyaltyUser();
        sameCustomerDifferentProjection.setId(UUID.randomUUID());
        sameCustomerDifferentProjection.setPhoneNumber(HOLDER_PHONE);
        when(userService.findOrCreatePending(eq(TENANT), eq(HOLDER_PHONE), any()))
                .thenReturn(sameCustomerDifferentProjection);

        assertThatThrownBy(() -> service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, HOLDER_PHONE, null)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("to yourself");
    }

    @Test
    void neitherRecipientField_isRefused() {
        assertThatThrownBy(() -> service.transfer(TENANT, UUID.randomUUID(),
                new Dtos.VoucherTransferRequest(null, null, null)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("exactly one");
        // Checked before the load, so a malformed request never takes the
        // pessimistic row lock.
        verify(vouchers, never()).lockById(any());
    }

    @Test
    void bothRecipientFields_isRefused() {
        assertThatThrownBy(() -> service.transfer(TENANT, UUID.randomUUID(),
                new Dtos.VoucherTransferRequest(UUID.randomUUID(), RECIPIENT_PHONE, null)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void aVoucherFromAnotherTenant_isRefused() {
        Voucher v = liveVoucher();
        v.setTenantId(UUID.randomUUID());
        stubLookup(v);

        assertThatThrownBy(() -> service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, RECIPIENT_PHONE, null)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("wrong tenant");
    }

    // ---- side effects ----

    @Test
    void transferResetsThePreExpiryWarning_soTheNewHolderGetsTheirOwn() {
        // expiryWarnedAt records that the PREVIOUS holder was warned. Left set,
        // ExpiryWarningSweeper skips the row and the new holder never hears the
        // voucher is about to lapse.
        Voucher v = liveVoucher();
        v.setExpiryWarnedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        stubLookup(v);
        stubRecipient();

        service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, RECIPIENT_PHONE, null));

        assertThat(v.getExpiryWarnedAt()).isNull();
    }

    @Test
    void transferKeepsTheDeliveryHistory() {
        // delivered/viewed are history, not the new holder's to reset.
        Voucher v = liveVoucher();
        Instant delivered = Instant.now().minus(3, ChronoUnit.DAYS);
        v.setDeliveredAt(delivered);
        stubLookup(v);
        stubRecipient();

        service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, RECIPIENT_PHONE, null));

        assertThat(v.getDeliveredAt()).isEqualTo(delivered);
    }

    @Test
    void bothSidesAreNotified() {
        // Without the RECEIVED message the transfer is silent: the voucher
        // lands in a wallet the recipient has no reason to open, and vouchers
        // still expire (365d default) even though points no longer do — so a
        // silent transfer can simply lapse unused, defeating the point of
        // handing it over. The SENT message is the sender's only confirmation
        // that a hand-typed phone number resolved to the person they meant.
        Voucher v = liveVoucher();
        stubLookup(v);
        stubRecipient();

        service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, RECIPIENT_PHONE, null));

        verify(memberNotifier).notifyVoucherReceived(eq(RECIPIENT_PHONE), eq("PERCENT"),
                eq(new BigDecimal("10.0000")), eq("USD"), any());
        verify(memberNotifier).notifyVoucherSent(eq(HOLDER_PHONE), eq("PERCENT"),
                eq(new BigDecimal("10.0000")), eq("USD"));
    }

    @Test
    void aRefusedTransferNotifiesNobody() {
        // A rejection must not text either party about a transfer that did not
        // happen.
        Voucher v = liveVoucher();
        v.setTransferredAt(Instant.now().minus(1, ChronoUnit.DAYS));
        stubLookup(v);

        assertThatThrownBy(() -> service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, RECIPIENT_PHONE, null)))
                .isInstanceOf(LoyaltyException.class);

        verify(memberNotifier, never()).notifyVoucherReceived(any(), any(), any(), any(), any());
        verify(memberNotifier, never()).notifyVoucherSent(any(), any(), any(), any());
    }

    @Test
    void anUnregisteredRecipient_isAutoEnrolledAsPending() {
        // You can pass a voucher to someone who hasn't signed up; it becomes
        // redeemable once they register.
        Voucher v = liveVoucher();
        stubLookup(v);
        stubRecipient();

        service.transfer(TENANT, v.getId(),
                new Dtos.VoucherTransferRequest(null, RECIPIENT_PHONE, null));

        verify(userService).findOrCreatePending(TENANT, RECIPIENT_PHONE, MERCHANT);
    }

    // ---- fixtures ----

    private Voucher liveVoucher() {
        Voucher v = new Voucher();
        v.setId(UUID.randomUUID());
        v.setTenantId(TENANT);
        v.setMerchantId(MERCHANT);
        v.setTemplateId(UUID.randomUUID());
        v.setCode("VCH-AB12CD34");
        v.setSignature("sig");
        v.setStatus(Voucher.Status.VIEWED);
        v.setAssignedUserId(UUID.randomUUID());
        v.setAssigneePhone(HOLDER_PHONE);
        v.setUsesRemaining(1);
        v.setIssuedAt(Instant.now().minus(5, ChronoUnit.DAYS));
        v.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        v.setValueType(com.innbucks.loyaltyservice.entity.VoucherTemplate.ValueType.PERCENT);
        v.setValue(new BigDecimal("10.0000"));
        v.setCurrency("USD");
        return v;
    }

    private void stubLookup(Voucher v) {
        when(vouchers.lockById(v.getId())).thenReturn(Optional.of(v));
    }

    private void stubRecipient() {
        LoyaltyUser recipient = new LoyaltyUser();
        recipient.setId(UUID.randomUUID());
        recipient.setPhoneNumber(RECIPIENT_PHONE);
        when(userService.findOrCreatePending(eq(TENANT), eq(RECIPIENT_PHONE), any()))
                .thenReturn(recipient);
    }

    private static void authenticateAs(String phone, String... roles) {
        var auth = new UsernamePasswordAuthenticationToken(
                "caller@example.com", null,
                List.of(roles).stream().map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new CallerDetails(null, null, phone, UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
