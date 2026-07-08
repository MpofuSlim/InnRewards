package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.FraudAttemptRepository;
import com.innbucks.loyaltyservice.repository.InvoiceRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.VoucherTemplateRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the merchant-360 report ({@code /loyalty/reports/merchants/full}):
 * <ul>
 *   <li>assembly — points/voucher/invoice/rule/shop blocks land in the right
 *       fields with the right rollups;</li>
 *   <li>visibility — the A01 ownership model is enforced in the SERVICE, so an
 *       out-of-scope merchant is absent for MERCHANT_ADMIN (adminEmail match)
 *       and SHOP_ADMIN (token merchant pin), while tenant-level admins see all;</li>
 *   <li>pagination — the slice happens AFTER the visibility filter.</li>
 * </ul>
 * Same pure-Mockito style as {@link ReportingServiceTest}; auth arrives via a
 * hand-built SecurityContext (CallerDetails reads the static holder).
 */
class ReportingServiceMerchantFullReportTest {

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
    private final ShopService shopService = mock(ShopService.class);
    private final ShopRepository shops = mock(ShopRepository.class);
    private final VoucherTemplateRepository voucherTemplates = mock(VoucherTemplateRepository.class);
    private final VoucherRedemptionRepository voucherRedemptions = mock(VoucherRedemptionRepository.class);
    private final LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);

    private final ReportingService reporting = new ReportingService(
            tenants, merchants, users, transactions, vouchers, invoices, fraud, campaigns,
            merchantService, userService, shopService, shops, voucherTemplates, voucherRedemptions, rules);

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID M_ALPHA = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
    private static final UUID M_BETA  = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");
    private static final UUID M_GAMMA = UUID.fromString("cccccccc-1111-2222-3333-444444444444");

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String email, CallerDetails details, String... roles) {
        var authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        var token = new UsernamePasswordAuthenticationToken(email, "n/a", authorities);
        token.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static Merchant merchant(UUID id, String name, String adminEmail) {
        Merchant m = new Merchant();
        m.setId(id);
        m.setTenantId(TENANT);
        m.setName(name);
        m.setCategory("Coffee");
        m.setCurrency("USD");
        m.setAdminEmail(adminEmail);
        return m;
    }

    private static Invoice invoice(UUID merchantId, String amount, Invoice.Status status) {
        Invoice inv = new Invoice();
        inv.setTenantId(TENANT);
        inv.setMerchantId(merchantId);
        inv.setInvoiceNumber("INV-" + amount);
        inv.setPeriodStart(java.time.LocalDate.of(2026, 6, 1));
        inv.setPeriodEnd(java.time.LocalDate.of(2026, 6, 30));
        inv.setTotalAmount(new BigDecimal(amount));
        inv.setStatus(status);
        return inv;
    }

    @Test
    void superAdmin_seesAllMerchants_nameOrdered_withFullAssembly() {
        Merchant alpha = merchant(M_ALPHA, "Alpha Cafe", "alpha@innbucks.co.zw");
        Merchant beta = merchant(M_BETA, "Beta Fuel", "beta@innbucks.co.zw");
        // Repo returns out of order — the service must sort by name.
        when(merchants.findByTenantId(TENANT)).thenReturn(List.of(beta, alpha));

        // Rules: one Alpha-specific, one tenant-global, one Beta-specific (must
        // NOT leak into Alpha's block).
        LoyaltyRule own = new LoyaltyRule();
        own.setId(UUID.randomUUID());
        own.setTenantId(TENANT);
        own.setMerchantId(M_ALPHA);
        own.setTransactionType(TransactionType.PURCHASE);
        LoyaltyRule global = new LoyaltyRule();
        global.setId(UUID.randomUUID());
        global.setTenantId(TENANT);
        global.setTransactionType(TransactionType.PURCHASE);
        LoyaltyRule other = new LoyaltyRule();
        other.setId(UUID.randomUUID());
        other.setTenantId(TENANT);
        other.setMerchantId(M_BETA);
        other.setTransactionType(TransactionType.PURCHASE);
        when(rules.findByTenantId(TENANT)).thenReturn(List.of(own, global, other));

        // Alpha's numbers.
        when(transactions.sumPointsIssued(eq(M_ALPHA), any(), any())).thenReturn(new BigDecimal("100"));
        when(transactions.sumPointsRedeemed(eq(M_ALPHA), any(), any())).thenReturn(new BigDecimal("40"));
        when(transactions.countByMerchantIdAndCreatedAtBetween(eq(M_ALPHA), any(), any())).thenReturn(7L);
        when(transactions.countByType(eq(TENANT), eq(M_ALPHA), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{TransactionType.PURCHASE, 5L},
                        new Object[]{TransactionType.REDEMPTION, 2L}));
        when(vouchers.reportSummaryByStatus(eq(null), eq(null), eq(M_ALPHA), eq(null), any(), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{Voucher.Status.ISSUED, 2L, new BigDecimal("30")},
                        new Object[]{Voucher.Status.REDEEMED, 3L, new BigDecimal("45")}));
        when(vouchers.sumRedeemedValueByMerchantId(M_ALPHA)).thenReturn(new BigDecimal("45"));
        when(invoices.findByMerchantIdOrderByPeriodEndDesc(M_ALPHA)).thenReturn(List.of(
                invoice(M_ALPHA, "10.00", Invoice.Status.PAID),
                invoice(M_ALPHA, "5.00", Invoice.Status.PENDING)));
        Shop shop = new Shop();
        shop.setId(UUID.randomUUID());
        shop.setTenantId(TENANT);
        shop.setMerchantId(M_ALPHA);
        shop.setName("Alpha Cafe Westgate");
        when(shops.findByTenantIdAndMerchantId(TENANT, M_ALPHA)).thenReturn(List.of(shop));
        when(transactions.countDistinctUsersByMerchantId(M_ALPHA)).thenReturn(3L);

        authenticate("op@innbucks.co.zw", new CallerDetails(null, null, null, null), "ROLE_SUPER_ADMIN");
        Page<Dtos.MerchantFullReport> page = reporting.merchantFullReports(TENANT, PageRequest.of(0, 20));

        assertEquals(2, page.getTotalElements());
        assertEquals("Alpha Cafe", page.getContent().get(0).name());
        assertEquals("Beta Fuel", page.getContent().get(1).name());

        Dtos.MerchantFullReport alphaReport = page.getContent().get(0);
        // points
        assertEquals(0, new BigDecimal("100").compareTo(alphaReport.points().issuedAllTime()));
        assertEquals(0, new BigDecimal("60").compareTo(alphaReport.points().netOutstanding()));
        assertEquals(7L, alphaReport.points().transactionCount());
        assertEquals(5L, alphaReport.points().transactionsByType().get("PURCHASE"));
        // vouchers
        assertEquals(5L, alphaReport.vouchers().total());
        assertEquals(2L, alphaReport.vouchers().byStatus().get("ISSUED"));
        assertEquals(0, new BigDecimal("75").compareTo(alphaReport.vouchers().valueIssuedAllTime()));
        assertEquals(0, new BigDecimal("45").compareTo(alphaReport.vouchers().valueRedeemedAllTime()));
        // invoices
        assertEquals(2, alphaReport.invoices().total());
        assertEquals(1, alphaReport.invoices().pending());
        assertEquals(1, alphaReport.invoices().paid());
        assertEquals(0, new BigDecimal("15.00").compareTo(alphaReport.invoices().totalBilled()));
        assertEquals(0, new BigDecimal("10.00").compareTo(alphaReport.invoices().totalPaid()));
        assertEquals(0, new BigDecimal("5.00").compareTo(alphaReport.invoices().outstandingAmount()));
        assertNotNull(alphaReport.invoices().nextInvoiceDate());
        assertEquals(2, alphaReport.invoices().recentInvoices().size());
        // rules: own + global, Beta's excluded; scopes labelled
        assertEquals(2, alphaReport.rules().size());
        assertTrue(alphaReport.rules().stream().anyMatch(r -> r.scope().equals("MERCHANT")));
        assertTrue(alphaReport.rules().stream().anyMatch(r -> r.scope().equals("TENANT_GLOBAL")));
        // shops + stats
        assertEquals(1, alphaReport.shops().size());
        assertEquals(1, alphaReport.stats().shopCount());
        assertEquals(1, alphaReport.stats().activeShopCount());
        assertEquals(3L, alphaReport.stats().uniqueCustomers());
    }

    @Test
    void merchantAdmin_seesOnlyMerchantsTheyAdminister_caseInsensitive() {
        when(merchants.findByTenantId(TENANT)).thenReturn(List.of(
                merchant(M_ALPHA, "Alpha Cafe", "Owner@Innbucks.co.zw"),
                merchant(M_BETA, "Beta Fuel", "someone-else@innbucks.co.zw"),
                merchant(M_GAMMA, "Gamma Groceries", null)));

        authenticate("owner@innbucks.co.zw", new CallerDetails(null, null, null, null),
                "ROLE_MERCHANT_ADMIN");
        Page<Dtos.MerchantFullReport> page = reporting.merchantFullReports(TENANT, PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals(M_ALPHA, page.getContent().get(0).id());
    }

    @Test
    void shopAdmin_isPinnedToTheMerchantInTheirToken() {
        when(merchants.findByTenantId(TENANT)).thenReturn(List.of(
                merchant(M_ALPHA, "Alpha Cafe", "alpha@innbucks.co.zw"),
                merchant(M_BETA, "Beta Fuel", "beta@innbucks.co.zw")));

        authenticate("cashier@innbucks.co.zw", new CallerDetails(M_BETA, null, null, null),
                "ROLE_SHOP_ADMIN");
        Page<Dtos.MerchantFullReport> page = reporting.merchantFullReports(TENANT, PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals(M_BETA, page.getContent().get(0).id());
    }

    @Test
    void paginationSlicesAfterTheVisibilityFilter() {
        when(merchants.findByTenantId(TENANT)).thenReturn(List.of(
                merchant(M_ALPHA, "Alpha", "a@x.zw"),
                merchant(M_BETA, "Beta", "b@x.zw"),
                merchant(M_GAMMA, "Gamma", "c@x.zw")));

        authenticate("op@innbucks.co.zw", new CallerDetails(null, null, null, null), "ROLE_SUPER_ADMIN");
        Page<Dtos.MerchantFullReport> page = reporting.merchantFullReports(TENANT, PageRequest.of(1, 2));

        assertEquals(3, page.getTotalElements());
        assertEquals(1, page.getContent().size());
        assertEquals("Gamma", page.getContent().get(0).name());
    }

    @Test
    void unauthenticatedCaller_seesNothing() {
        when(merchants.findByTenantId(TENANT)).thenReturn(List.of(
                merchant(M_ALPHA, "Alpha", "a@x.zw")));

        // No SecurityContext at all (defence in depth — the controller's
        // @PreAuthorize should have rejected this long before).
        Page<Dtos.MerchantFullReport> page = reporting.merchantFullReports(TENANT, PageRequest.of(0, 20));

        assertEquals(0, page.getTotalElements());
    }
}
