package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the guard rails on the TEST-ONLY unauthenticated surface.
 *
 * <p>Pure JUnit + Mockito — no {@code @SpringBootTest}, per the repo's
 * no-Docker-sandbox convention. The behaviour that matters here is the
 * controller's own gating and clamping, not Spring wiring.
 */
class PublicTestControllerTest {

    private static final String PHONE = "+263771234567";

    private final LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final com.innbucks.loyaltyservice.repository.WalletRepository wallets =
            mock(com.innbucks.loyaltyservice.repository.WalletRepository.class);
    private final com.innbucks.loyaltyservice.repository.VoucherRepository vouchers =
            mock(com.innbucks.loyaltyservice.repository.VoucherRepository.class);
    private final com.innbucks.loyaltyservice.repository.MerchantRepository merchants =
            mock(com.innbucks.loyaltyservice.repository.MerchantRepository.class);
    private final com.innbucks.loyaltyservice.service.TransferService transfers =
            mock(com.innbucks.loyaltyservice.service.TransferService.class);
    private final com.innbucks.loyaltyservice.service.RedemptionService redemptions =
            mock(com.innbucks.loyaltyservice.service.RedemptionService.class);
    private final com.innbucks.loyaltyservice.service.VoucherService voucherService =
            mock(com.innbucks.loyaltyservice.service.VoucherService.class);

    private PublicTestController controller(boolean enabled) {
        PublicTestController c = new PublicTestController(users, wallets, vouchers, merchants,
                transactions, transfers, redemptions, voucherService);
        ReflectionTestUtils.setField(c, "enabled", enabled);
        return c;
    }

    // ---- the master switch ----

    @Test
    void disabledByDefault_returns404_andNeverTouchesTheDatabase() {
        // Fail-closed is the whole safety story: a cell that never sets
        // LOYALTY_PUBLIC_TEST_ENABLED must not serve customer history to
        // anonymous callers. 404 (not 403) so a disabled cell doesn't advertise
        // that there's a feature here to unlock.
        PublicTestController c = controller(false);

        assertThatThrownBy(() -> c.transactionsByPhone(PHONE, PageRequest.of(0, 20)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("not enabled");

        verify(users, never()).findByPhoneNumber(any());
        verify(transactions, never()).statementForPhone(anyList(), any());
    }

    @Test
    void enabled_returnsTheStatement() {
        UUID userId = UUID.randomUUID();
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of(user(userId)));
        when(transactions.statementForPhone(eq(List.of(userId)), any())).thenReturn(oneRow());

        ResponseEntity<ApiResult<PageResponse<Dtos.TransactionResponse>>> resp =
                controller(true).transactionsByPhone(PHONE, PageRequest.of(0, 20));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData().getContent()).hasSize(1);
    }

    // ---- cross-tenant collapse ----

    @Test
    void collapsesEveryTenantProjectionForThePhoneIntoOneFeed() {
        // A phone maps to one LoyaltyUser per tenant. The customer has one
        // history, so all projections must go into a single query — paginating
        // per projection and stitching would mis-order the feed.
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of(user(a), user(b)));
        when(transactions.statementForPhone(anyList(), any())).thenReturn(oneRow());

        controller(true).transactionsByPhone(PHONE, PageRequest.of(0, 20));

        verify(transactions).statementForPhone(eq(List.of(a, b)), any());
    }

    @Test
    void unknownPhone_returnsEmptyPage_notA404() {
        // Deliberate: to a caller holding only a phone number, "not a customer"
        // and "no activity yet" must be indistinguishable, or the endpoint
        // becomes a registration oracle for anyone enumerating numbers.
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of());
        when(transactions.statementForPhone(eq(List.of()), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        ResponseEntity<ApiResult<PageResponse<Dtos.TransactionResponse>>> resp =
                controller(true).transactionsByPhone(PHONE, PageRequest.of(0, 20));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getData().getContent()).isEmpty();
        assertThat(resp.getBody().getData().getTotalElements()).isZero();
    }

    // ---- input guards ----

    @Test
    void blankPhone_isRejectedBeforeAnyLookup() {
        PublicTestController c = controller(true);

        assertThatThrownBy(() -> c.transactionsByPhone("   ", PageRequest.of(0, 20)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("phoneNumber is required");

        verify(users, never()).findByPhoneNumber(any());
    }

    @Test
    void oversizedPageRequest_isClampedTo100() {
        // An unauthenticated caller must not be able to ask for the whole table
        // in one request.
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of(user(UUID.randomUUID())));
        when(transactions.statementForPhone(anyList(), any())).thenReturn(oneRow());

        controller(true).transactionsByPhone(PHONE, PageRequest.of(3, 5_000));

        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(transactions).statementForPhone(anyList(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        assertThat(captor.getValue().getPageNumber())
                .as("clamping the size must not move the caller off their page")
                .isEqualTo(3);
    }

    @Test
    void reasonablePageRequest_isPassedThroughUnchanged() {
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of(user(UUID.randomUUID())));
        when(transactions.statementForPhone(anyList(), any())).thenReturn(oneRow());

        controller(true).transactionsByPhone(PHONE, PageRequest.of(1, 25));

        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(transactions).statementForPhone(anyList(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(25);
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);
    }

    // ---- writes: the tenant/merchant resolution that replaces the header ----

    @Test
    void sendPoints_resolvesTheSenderFromThePhone_soNoIdsAreSentByTheClient() {
        // The whole point of the public surface: the FE sends a phone and an
        // amount. No tenantId, no fromUserId, no headers.
        UUID senderId = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        LoyaltyUser sender = user(senderId);
        sender.setTenantId(tenant);
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of(sender));
        when(transfers.transfer(any(), any())).thenReturn(new java.math.BigDecimal("75.00"));

        controller(true).sendPoints(PHONE, new PublicTestController.PublicSendPointsRequest(
                "+263772222222", new java.math.BigDecimal("25"), "gift"));

        var req = org.mockito.ArgumentCaptor.forClass(Dtos.TransferRequest.class);
        verify(transfers).transfer(eq(tenant), req.capture());
        assertThat(req.getValue().fromUserId())
                .as("sender resolved from the URL phone, not supplied by the caller")
                .isEqualTo(senderId);
        assertThat(req.getValue().toPhone()).isEqualTo("+263772222222");
    }

    @Test
    void sendPoints_refusesRatherThanGuessing_whenThePhoneSpansSeveralTenants() {
        // Picking a tenant arbitrarily would move points in one the caller
        // never chose, and the mistake would be silent.
        LoyaltyUser a = user(UUID.randomUUID());
        a.setTenantId(UUID.randomUUID());
        LoyaltyUser b = user(UUID.randomUUID());
        b.setTenantId(UUID.randomUUID());
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of(a, b));
        PublicTestController c = controller(true);

        assertThatThrownBy(() -> c.sendPoints(PHONE,
                new PublicTestController.PublicSendPointsRequest(
                        "+263772222222", new java.math.BigDecimal("25"), null)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("more than one tenant");

        verify(transfers, never()).transfer(any(), any());
    }

    @Test
    void redeemPoints_fallsBackToTheTenantsOnlyMerchant() {
        // A single-tenant test cell should need no config at all.
        UUID tenant = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        LoyaltyUser customer = user(UUID.randomUUID());
        customer.setTenantId(tenant);
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of(customer));
        com.innbucks.loyaltyservice.entity.Merchant m =
                new com.innbucks.loyaltyservice.entity.Merchant();
        m.setId(merchantId);
        when(merchants.findByTenantId(tenant)).thenReturn(List.of(m));
        when(redemptions.redeemPointsIdempotent(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.innbucks.loyaltyservice.service.RedemptionService.RedemptionResult(
                        UUID.randomUUID(), new java.math.BigDecimal("25.00")));

        controller(true).redeemPoints(PHONE, new PublicTestController.PublicRedeemPointsRequest(
                new java.math.BigDecimal("500"), null, null, "ORDER-1"));

        verify(redemptions).redeemPointsIdempotent(eq(tenant), eq(merchantId), any(),
                org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void redeemPoints_refusesWhenTheTenantHasSeveralMerchants() {
        UUID tenant = UUID.randomUUID();
        LoyaltyUser customer = user(UUID.randomUUID());
        customer.setTenantId(tenant);
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of(customer));
        var m1 = new com.innbucks.loyaltyservice.entity.Merchant();
        m1.setId(UUID.randomUUID());
        var m2 = new com.innbucks.loyaltyservice.entity.Merchant();
        m2.setId(UUID.randomUUID());
        when(merchants.findByTenantId(tenant)).thenReturn(List.of(m1, m2));
        PublicTestController c = controller(true);

        assertThatThrownBy(() -> c.redeemPoints(PHONE,
                new PublicTestController.PublicRedeemPointsRequest(
                        new java.math.BigDecimal("500"), null, null, null)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("Supply merchantId");
    }

    @Test
    void voucherTransfer_takesTheTenantFromTheVoucherItself() {
        // No pin and no phone needed — the voucher row already knows.
        UUID tenant = UUID.randomUUID();
        UUID voucherId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        com.innbucks.loyaltyservice.entity.Voucher v =
                new com.innbucks.loyaltyservice.entity.Voucher();
        v.setId(voucherId);
        v.setTenantId(tenant);
        v.setAssignedUserId(holderId);
        when(vouchers.findById(voucherId)).thenReturn(java.util.Optional.of(v));
        when(users.findById(holderId)).thenReturn(java.util.Optional.of(user(holderId)));

        controller(true).transferVoucher(voucherId,
                new PublicTestController.PublicVoucherTransferRequest("+263772222222", null));

        verify(voucherService).transfer(eq(tenant), eq(voucherId), any());
    }

    // ---- the switch still governs the writes ----

    @Test
    void everyWriteIsAlsoDeadWhenDisabled() {
        PublicTestController c = controller(false);

        assertThatThrownBy(() -> c.sendPoints(PHONE,
                new PublicTestController.PublicSendPointsRequest(
                        "+263772222222", new java.math.BigDecimal("1"), null)))
                .isInstanceOf(LoyaltyException.class).hasMessageContaining("not enabled");
        assertThatThrownBy(() -> c.redeemPoints(PHONE,
                new PublicTestController.PublicRedeemPointsRequest(
                        new java.math.BigDecimal("1"), null, null, null)))
                .isInstanceOf(LoyaltyException.class).hasMessageContaining("not enabled");
        assertThatThrownBy(() -> c.transferVoucher(UUID.randomUUID(),
                new PublicTestController.PublicVoucherTransferRequest("+263772222222", null)))
                .isInstanceOf(LoyaltyException.class).hasMessageContaining("not enabled");
        assertThatThrownBy(() -> c.redeemVoucher(
                new PublicTestController.PublicRedeemVoucherRequest("VCH-1", null)))
                .isInstanceOf(LoyaltyException.class).hasMessageContaining("not enabled");

        // Nothing reached the database or any service.
        verify(users, never()).findByPhoneNumber(any());
        verify(vouchers, never()).findById(any());
        verify(transfers, never()).transfer(any(), any());
    }

    private static LoyaltyUser user(UUID id) {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(id);
        u.setPhoneNumber(PHONE);
        return u;
    }

    private static Page<Dtos.TransactionResponse> oneRow() {
        Dtos.TransactionResponse row = new Dtos.TransactionResponse(
                UUID.randomUUID(), TransactionType.PURCHASE, new BigDecimal("100.00"),
                new BigDecimal("10.00"), null, null, null, null,
                null, null, "ORDER-4471", Instant.parse("2026-08-24T09:15:00Z"), null);
        return new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1);
    }
}
