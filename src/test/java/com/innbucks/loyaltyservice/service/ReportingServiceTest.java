package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.FraudAttemptRepository;
import com.innbucks.loyaltyservice.repository.InvoiceRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Guards the cross-tenant fix on the three reporting endpoints that previously
 * leaked another tenant's data ({@code /reports/merchant/{id}},
 * {@code /reports/points/merchant/{id}}, {@code /reports/points/user/{id}}).
 * Each now resolves the path id through the owning service's tenant guard
 * ({@code requireMerchant} / {@code require}) before touching any data, so a
 * foreign id throws CROSS_TENANT (403) and no rows are read.
 */
class ReportingServiceTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);
    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final FraudAttemptRepository fraud = mock(FraudAttemptRepository.class);
    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final MerchantService merchantService = mock(MerchantService.class);
    private final UserService userService = mock(UserService.class);

    private final ReportingService reporting = new ReportingService(
            tenants, merchants, users, transactions, vouchers, invoices, fraud, campaigns,
            merchantService, userService);

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MERCHANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_B = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final LocalDate FROM = LocalDate.of(2026, 5, 1);
    private static final LocalDate TO = LocalDate.of(2026, 5, 31);

    @Test
    void merchant_throwsCrossTenant_andReadsNoData_whenMerchantInAnotherTenant() {
        when(merchantService.requireMerchant(TENANT_A, MERCHANT_B))
                .thenThrow(LoyaltyException.forbidden("CROSS_TENANT", "merchant belongs to a different tenant"));

        LoyaltyException ex = assertThrows(LoyaltyException.class,
                () -> reporting.merchant(TENANT_A, MERCHANT_B));
        assertTrue(ex.getMessage().contains("different tenant"));

        // The guard fires before any aggregation — no merchant data is read.
        verify(transactions, never()).sumPointsIssued(any(), any(), any());
        verify(vouchers, never()).findByMerchantIdAndIssuedAtBetween(any(), any(), any());
    }

    @Test
    void pointsForMerchant_throwsCrossTenant_andReadsNoData_whenMerchantInAnotherTenant() {
        when(merchantService.requireMerchant(TENANT_A, MERCHANT_B))
                .thenThrow(LoyaltyException.forbidden("CROSS_TENANT", "merchant belongs to a different tenant"));

        assertThrows(LoyaltyException.class,
                () -> reporting.pointsForMerchant(TENANT_A, MERCHANT_B, FROM, TO));

        verify(transactions, never()).sumPointsIssued(any(), any(), any());
        verify(transactions, never()).countByMerchantIdAndCreatedAtBetween(any(), any(), any());
    }

    @Test
    void pointsForUser_throwsCrossTenant_andReadsNoData_whenUserInAnotherTenant() {
        when(userService.require(TENANT_A, USER_B))
                .thenThrow(LoyaltyException.forbidden("CROSS_TENANT", "user belongs to a different tenant"));

        LoyaltyException ex = assertThrows(LoyaltyException.class,
                () -> reporting.pointsForUser(TENANT_A, USER_B, FROM, TO));
        assertTrue(ex.getMessage().contains("different tenant"));

        verify(transactions, never()).sumPointsIssuedByUser(any(), any(), any());
        verify(transactions, never()).countByUserIdAndCreatedAtBetween(any(), any(), any());
    }

    @Test
    void pointsForMerchant_passesGuard_andReturnsReport_whenSameTenant() {
        // requireMerchant does not throw (same tenant) — the report is produced
        // and the data is actually read, proving the guard doesn't block the
        // legitimate path.
        when(transactions.sumPointsIssued(eq(MERCHANT_B), any(), any())).thenReturn(new BigDecimal("100"));
        when(transactions.sumPointsRedeemed(eq(MERCHANT_B), any(), any())).thenReturn(new BigDecimal("40"));

        var report = reporting.pointsForMerchant(TENANT_A, MERCHANT_B, FROM, TO);

        assertNotNull(report);
        verify(merchantService).requireMerchant(TENANT_A, MERCHANT_B);
        verify(transactions).sumPointsIssued(eq(MERCHANT_B), any(), any());
    }

    @Test
    void pointsForUserByPhone_resolvesByPhone_andReturnsReport_keyedByResolvedUserId() {
        String phone = "+263771234567";
        LoyaltyUser u = new LoyaltyUser();
        u.setId(USER_B);
        u.setTenantId(TENANT_A);
        u.setPhoneNumber(phone);
        when(userService.requireByPhone(TENANT_A, phone)).thenReturn(u);
        when(transactions.sumPointsIssuedByUser(eq(USER_B), any(), any())).thenReturn(new BigDecimal("1240"));
        when(transactions.sumPointsRedeemedByUser(eq(USER_B), any(), any())).thenReturn(new BigDecimal("500"));
        when(transactions.countByUserIdAndCreatedAtBetween(eq(USER_B), any(), any())).thenReturn(18L);

        Dtos.PointsReport report = reporting.pointsForUserByPhone(TENANT_A, phone, FROM, TO);

        // Resolved by phone, but the statement is keyed by (and reports) the LoyaltyUser UUID.
        assertEquals(USER_B, report.subjectId());
        assertEquals(0, report.netPoints().compareTo(new BigDecimal("740")));
        assertEquals(18L, report.transactionCount());
        verify(userService).requireByPhone(TENANT_A, phone);
        verify(userService, never()).require(any(), any());
    }

    @Test
    void pointsForUserByPhone_unknownPhone_throwsNotFound_andReadsNoData() {
        String phone = "+263770000000";
        when(userService.requireByPhone(TENANT_A, phone))
                .thenThrow(LoyaltyException.notFound("user"));

        assertThrows(LoyaltyException.class,
                () -> reporting.pointsForUserByPhone(TENANT_A, phone, FROM, TO));

        verify(transactions, never()).sumPointsIssuedByUser(any(), any(), any());
        verify(transactions, never()).countByUserIdAndCreatedAtBetween(any(), any(), any());
    }

    @Test
    void operator_excludesTicketingTenantAndItsMerchant_fromEveryFigure() {
        UUID ticketing = TicketingLoyaltyService.TICKETING_TENANT_ID;
        UUID realMerchantId = UUID.randomUUID();

        Merchant real = new Merchant();
        real.setId(realMerchantId);
        real.setTenantId(TENANT_A);
        real.setStatus(Merchant.Status.ACTIVE);

        Merchant ticketMerchant = new Merchant();
        ticketMerchant.setId(UUID.randomUUID());
        ticketMerchant.setTenantId(ticketing);
        ticketMerchant.setStatus(Merchant.Status.ACTIVE);

        when(tenants.countByIdNot(ticketing)).thenReturn(2L);
        when(merchants.findAll()).thenReturn(List.of(real, ticketMerchant));
        when(transactions.countSinceExcludingTenant(any(), eq(ticketing))).thenReturn(5L);
        when(transactions.sumPointsIssued(eq(realMerchantId), any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactions.sumPointsRedeemed(eq(realMerchantId), any(), any())).thenReturn(BigDecimal.ZERO);
        when(vouchers.findExpired(any())).thenReturn(List.of());

        Dtos.OperatorDashboard d = reporting.operator();

        assertEquals(2L, d.totalTenants());     // ticketing tenant NOT counted (dashboard was showing 3)
        assertEquals(1L, d.activeMerchants());  // ticketing merchant excluded
        assertEquals(5L, d.transactionsToday());
        // The ticketing merchant's activity is never aggregated, and the plain
        // count() (which includes ticketing) is never used.
        verify(transactions, never()).sumPointsIssued(eq(ticketMerchant.getId()), any(), any());
        verify(tenants, never()).count();
    }

    @Test
    void pointsForShop_includesPerPhoneBreakdown_sortedByIssuedDesc_withNet() {
        UUID shopId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(transactions.sumPointsIssuedByShop(eq(TENANT_A), eq(shopId), any(), any()))
                .thenReturn(new BigDecimal("30"));
        when(transactions.sumPointsRedeemedByShop(eq(TENANT_A), eq(shopId), any(), any()))
                .thenReturn(new BigDecimal("5"));
        when(transactions.countByTenantIdAndShopIdAndCreatedAtBetween(eq(TENANT_A), eq(shopId), any(), any()))
                .thenReturn(3L);
        // Smaller earner returned first to prove the service sorts by issued desc.
        when(transactions.pointsByPhoneForShop(eq(TENANT_A), eq(shopId), any(), any()))
                .thenReturn(List.of(
                        new Object[]{"+263771111111", new BigDecimal("10"), new BigDecimal("0"), 1L},
                        new Object[]{"+263772222222", new BigDecimal("20"), new BigDecimal("5"), 2L}));

        Dtos.ShopPointsReport report = reporting.pointsForShop(TENANT_A, shopId, FROM, TO);

        assertEquals(shopId, report.subjectId());
        assertEquals(0, report.pointsIssued().compareTo(new BigDecimal("30")));
        assertEquals(0, report.netPoints().compareTo(new BigDecimal("25")));
        assertEquals(3L, report.transactionCount());

        assertEquals(2, report.byPhone().size());
        Dtos.PointsByPhoneRow top = report.byPhone().get(0);
        assertEquals("+263772222222", top.phoneNumber());          // highest earner first
        assertEquals(0, top.pointsIssued().compareTo(new BigDecimal("20")));
        assertEquals(0, top.netPoints().compareTo(new BigDecimal("15")));
        assertEquals(2L, top.transactionCount());
        assertEquals("+263771111111", report.byPhone().get(1).phoneNumber());
    }
}
