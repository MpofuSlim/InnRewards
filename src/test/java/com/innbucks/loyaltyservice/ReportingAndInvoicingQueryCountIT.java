package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.repository.InvoiceRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.service.InvoicingService;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.ReportingService;
import com.innbucks.loyaltyservice.service.TicketingLoyaltyService;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator dashboard, the merchant-360 report and the nightly invoice run
 * against REAL Postgres, measured with Hibernate statistics: what they cost must
 * not grow with the number of merchants. Each used to run its queries once per
 * merchant (the dashboard four, the 360 report seventeen per merchant on the
 * page, the invoice run seven per active merchant); each now runs a fixed set
 * of grouped queries and assembles per merchant in memory.
 *
 * <p>The container is shared with every other IT, so the database holds their
 * merchants too. That is fine for the counts — a grouped query costs one
 * statement whatever it returns — and the figures are checked against the
 * per-merchant queries they replaced, over the same data.
 */
class ReportingAndInvoicingQueryCountIT extends PostgresIntegrationTestBase {

    @Autowired TenantRepository tenants;
    @Autowired MerchantRepository merchants;
    @Autowired MerchantService merchantService;
    @Autowired VoucherService voucherService;
    @Autowired VoucherRepository vouchers;
    @Autowired LoyaltyTransactionRepository transactions;
    @Autowired InvoiceRepository invoices;
    @Autowired ReportingService reporting;
    @Autowired InvoicingService invoicing;
    @Autowired EntityManagerFactory emf;

    @MockitoBean UserServiceClient userServiceClient;

    private UUID tenantId;
    private Statistics stats;
    private int merchantSeq;

    @BeforeEach
    void seed() {
        Tenant t = new Tenant();
        t.setCode("rqc-" + UUID.randomUUID().toString().substring(0, 8));
        t.setName("Reporting Query Count");
        tenantId = tenants.save(t).getId();
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
    }

    @AfterEach
    void stopCounting() {
        stats.setStatisticsEnabled(false);
    }

    /** A DAILY merchant with a $0.05 issue fee, and {@code vouchers} vouchers issued now. */
    private UUID merchant(int vouchersIssued) {
        UUID id = merchantService.create(tenantId,
                new Dtos.MerchantRequest("RQC Merchant " + (++merchantSeq), "Retail", "USD",
                        Merchant.BillingCycle.DAILY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
        if (vouchersIssued > 0) {
            voucherService.issueBulk(tenantId, new Dtos.BulkIssueRequest(id, null, new BigDecimal("5.00"), "USD",
                    null, vouchersIssued, "rqc", null));
        }
        return id;
    }

    private List<UUID> merchants(int count, int vouchersEach) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(merchant(vouchersEach));
        }
        return ids;
    }

    /** {@code [queries executed, statements prepared]} by {@code body}. */
    private long[] measure(Runnable body) {
        stats.clear();
        body.run();
        return new long[]{stats.getQueryExecutionCount(), stats.getPrepareStatementCount()};
    }

    // ------------------------------------------------------------------ operator

    @Test
    void theOperatorDashboard_costsTheSameFor2MerchantsAndFor6() {
        merchants(2, 1);
        long[] two = measure(reporting::operator);
        merchants(4, 1);
        long[] six = measure(reporting::operator);

        assertThat(six[0]).as("queries, 6 merchants vs 2").isEqualTo(two[0]);
        assertThat(six[1]).as("statements, 6 merchants vs 2").isEqualTo(two[1]);
    }

    @Test
    void theOperatorDashboard_addsUpToWhatThePerMerchantQueriesSaid() {
        merchants(3, 2);
        Dtos.OperatorDashboard d = reporting.operator();

        // The loop this replaced, run over the same data.
        Instant startOfDay = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endOfDay = startOfDay.plusSeconds(86_400);
        UUID ticketing = TicketingLoyaltyService.TICKETING_TENANT_ID;
        BigDecimal issued = BigDecimal.ZERO;
        BigDecimal redeemed = BigDecimal.ZERO;
        long vIssued = 0;
        long vRedeemed = 0;
        long active = 0;
        for (Merchant m : merchants.findAll()) {
            if (ticketing.equals(m.getTenantId())) continue;
            if (m.getStatus() == Merchant.Status.ACTIVE) active++;
            issued = issued.add(transactions.sumPointsIssued(m.getId(), startOfDay, endOfDay));
            redeemed = redeemed.add(transactions.sumPointsRedeemed(m.getId(), startOfDay, endOfDay));
            vIssued += vouchers.countByMerchantIdAndIssuedAtBetween(m.getId(), startOfDay, endOfDay);
            vRedeemed += vouchers.countByMerchantIdAndRedeemedAtBetween(m.getId(), startOfDay, endOfDay);
        }
        assertThat(d.activeMerchants()).isEqualTo(active);
        assertThat(d.pointsIssuedToday()).isEqualTo(issued);       // equals: same value AND scale
        assertThat(d.pointsRedeemedToday()).isEqualTo(redeemed);
        assertThat(d.vouchersIssuedToday()).isEqualTo(vIssued);
        assertThat(d.vouchersRedeemedToday()).isEqualTo(vRedeemed);
        assertThat(d.expiringIn7Days())
                .isEqualTo(vouchers.findExpired(startOfDay.plusSeconds(7 * 86_400)).size());
    }

    // --------------------------------------------------------------- merchant-360

    @Test
    void theMerchant360Page_costsTheSameFor2MerchantsAndFor6() {
        merchants(2, 1);
        long[] two = measure(() -> assertThat(
                reporting.merchantFullReports(tenantId, PageRequest.of(0, 20)).getContent()).hasSize(2));
        merchants(4, 1);
        long[] six = measure(() -> assertThat(
                reporting.merchantFullReports(tenantId, PageRequest.of(0, 20)).getContent()).hasSize(6));

        assertThat(six[0]).as("queries, 6 merchants vs 2").isEqualTo(two[0]);
        assertThat(six[1]).as("statements, 6 merchants vs 2").isEqualTo(two[1]);
    }

    @Test
    void theMerchant360Figures_matchThePerMerchantQueries() {
        List<UUID> ids = merchants(2, 3);
        Page<Dtos.MerchantFullReport> page = reporting.merchantFullReports(tenantId, PageRequest.of(0, 20));
        Instant now = Instant.now();

        for (Dtos.MerchantFullReport r : page.getContent()) {
            assertThat(ids).contains(r.id());
            assertThat(r.vouchers().total()).isEqualTo(3);
            assertThat(r.vouchers().issuedLast30Days())
                    .isEqualTo(vouchers.countByMerchantIdAndIssuedAtBetween(r.id(),
                            now.minus(30, java.time.temporal.ChronoUnit.DAYS), now));
            assertThat(r.vouchers().valueRedeemedAllTime())
                    .isEqualByComparingTo(vouchers.sumRedeemedValueByMerchantId(r.id()));
            assertThat(r.points().issuedAllTime())
                    .isEqualByComparingTo(transactions.sumPointsIssued(r.id(), Instant.EPOCH, now));
            assertThat(r.stats().uniqueCustomers()).isEqualTo(transactions.countDistinctUsersByMerchantId(r.id()));
            // 3 vouchers issued today on a DAILY $0.05 issue fee.
            assertThat(r.invoices().estimatedCurrentPeriodFees()).isEqualByComparingTo("0.15");
        }
    }

    // ------------------------------------------------------------------ invoicing

    /** Tomorrow, so a DAILY merchant's period is today — the day its vouchers were issued. */
    private static LocalDate runDay() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(1);
    }

    @Test
    void theInvoiceRun_readsAFixedNumberOfTimes_andWritesAFixedAmountPerInvoice() {
        merchant(0);   // a DAILY period group exists from the start, so every run plans the same periods
        invoicing.runPeriodicForAllMerchants(runDay());   // invoice anything other ITs left billable
        long[] base = measure(() -> assertThat(invoicing.runPeriodicForAllMerchants(runDay())).isZero());

        // Merchants with nothing to bill cost nothing: 2 or 6 of them, same as none.
        merchants(2, 0);
        long[] twoIdle = measure(() -> assertThat(invoicing.runPeriodicForAllMerchants(runDay())).isZero());
        merchants(4, 0);
        long[] sixIdle = measure(() -> assertThat(invoicing.runPeriodicForAllMerchants(runDay())).isZero());
        assertThat(twoIdle).as("2 idle merchants vs none").containsExactly(base);
        assertThat(sixIdle).as("6 idle merchants vs none").containsExactly(base);

        // Billed merchants: the reads stay fixed; each invoice adds the same
        // short write transaction (re-check, two points sums, insert, stamp).
        merchants(2, 1);
        long[] twoBilled = measure(() -> assertThat(invoicing.runPeriodicForAllMerchants(runDay())).isEqualTo(2));
        merchants(6, 1);
        long[] sixBilled = measure(() -> assertThat(invoicing.runPeriodicForAllMerchants(runDay())).isEqualTo(6));

        for (int i = 0; i < 2; i++) {
            long perInvoice = (twoBilled[i] - base[i]) / 2;
            assertThat(twoBilled[i] - base[i]).as("an even cost per invoice").isEqualTo(2 * perInvoice);
            assertThat(sixBilled[i] - base[i])
                    .as(i == 0 ? "queries: 6 invoices cost 3x what 2 cost, over the same fixed reads"
                            : "statements: 6 invoices cost 3x what 2 cost, over the same fixed reads")
                    .isEqualTo(6 * perInvoice);
            assertThat(perInvoice).as("per-invoice cost").isLessThanOrEqualTo(6);
        }

        // And a re-run invoices nothing twice.
        assertThat(invoicing.runPeriodicForAllMerchants(runDay())).isZero();
    }

    @Test
    void theInvoiceRun_billsWhatGenerateWouldHaveBilled() {
        UUID id = merchant(4);
        invoicing.runPeriodicForAllMerchants(runDay());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Invoice inv = invoices.findByMerchantIdAndPeriodStartAndPeriodEnd(id, today, today).orElseThrow();
        assertThat(inv.getVouchersIssued()).isEqualTo(4);
        assertThat(inv.getVouchersRedeemed()).isZero();
        assertThat(inv.getTotalAmount()).isEqualByComparingTo("0.20");   // 4 x $0.05
        assertThat(inv.getStatus()).isEqualTo(Invoice.Status.PENDING);
    }
}
