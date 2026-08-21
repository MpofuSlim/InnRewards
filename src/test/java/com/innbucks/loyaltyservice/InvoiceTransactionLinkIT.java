package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.service.InvoicingService;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.RuleAdminService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.VoucherTemplateService;
import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * IN-9: transactions carry a back-reference to the invoice whose billing period
 * covered them, so a points report can name the bill a row was counted on.
 *
 * <p>The contract under test is narrow but load-bearing: <b>the rows stamped
 * must be exactly the rows the invoice's own points figures were summed from</b>.
 * If those two sets ever diverge, a report would point at an invoice whose
 * printed {@code pointsIssued} doesn't account for the row — which is worse than
 * having no link at all, because it looks authoritative.
 *
 * <p>Runs on Postgres via Testcontainers (requires Docker) because the stamping
 * is a bulk JPQL UPDATE and the interesting failure modes are in the SQL, not in
 * the Java.
 */
class InvoiceTransactionLinkIT extends PostgresIntegrationTestBase {

    @Autowired TenantRepository tenantRepository;
    @Autowired MerchantRepository merchantRepository;
    @Autowired MerchantService merchantService;
    @Autowired UserService userService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;
    @Autowired InvoicingService invoicingService;
    @Autowired LoyaltyTransactionRepository transactions;
    @Autowired VoucherService voucherService;
    @Autowired VoucherTemplateService voucherTemplateService;

    @MockitoBean UserServiceClient userServiceClient;

    private UUID tenantId;
    private UUID merchantId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));

        String phone = "+26378" + String.format("%07d", Math.abs(System.nanoTime() % 10_000_000L));

        Tenant t = new Tenant();
        t.setCode("inv-link-" + System.nanoTime());
        t.setName("Invoice Link Test");
        tenantId = tenantRepository.save(t).getId();

        merchantId = merchantService.create(tenantId,
                new Dtos.MerchantRequest("Invoice Link Cafe", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();

        ruleAdminService.createRule(tenantId, merchantId,
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        LoyaltyUser u = userService.findOrEnrol(tenantId, phone, merchantId);
        userId = u.getId();
    }

    @Test
    void transactionsStartUnlinked_untilTheirPeriodIsInvoiced() {
        earn(100, "earn-unlinked");

        assertThat(transactions.findAll().stream()
                .filter(t -> merchantId.equals(t.getMerchantId()))
                .map(LoyaltyTransaction::getInvoiceId))
                .as("nothing is billed before the invoice job runs")
                .containsOnlyNulls();
    }

    @Test
    void pointsAloneRaiseNoInvoice_soTheirRowsStayUnlinked() {
        // Invoice totals come from VOUCHER fees, not points, and
        // InvoicingService skips a zero-total invoice entirely. So a merchant
        // can issue points all period and still be billed nothing — in which
        // case there is no invoice for those rows to reference, and NULL is the
        // correct answer rather than a gap. Pinning it because it is genuinely
        // surprising: "points issued but invoiceId is null" looks like a bug
        // until you know invoices are voucher-priced.
        earn(100, "earn-no-vouchers");
        Merchant m = merchantRepository.findById(merchantId).orElseThrow();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        assertThat(invoicingService.generate(m, today.minusDays(1), today))
                .as("no billable voucher activity -> no invoice at all")
                .isEmpty();

        assertThat(transactions.findAll().stream()
                .filter(t -> merchantId.equals(t.getMerchantId()))
                .map(LoyaltyTransaction::getInvoiceId))
                .containsOnlyNulls();
    }

    @Test
    void generatingAnInvoiceStampsExactlyTheRowsItBilled() {
        earn(100, "earn-in-period");
        issueVoucher();   // gives the invoice a non-zero total so it is raised
        Merchant m = merchantRepository.findById(merchantId).orElseThrow();

        // Bill a window that certainly contains the earn we just made.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Invoice invoice = invoicingService.generate(m, today.minusDays(1), today).orElseThrow();

        List<LoyaltyTransaction> mine = transactions.findAll().stream()
                .filter(t -> merchantId.equals(t.getMerchantId()))
                .toList();
        assertThat(mine).isNotEmpty();
        assertThat(mine).allSatisfy(t ->
                assertThat(t.getInvoiceId())
                        .as("every row in the billed window is stamped")
                        .isEqualTo(invoice.getId()));

        // The stamped set must reconcile with what the invoice actually printed.
        BigDecimal stampedPointsIssued = mine.stream()
                .filter(t -> t.getStatus() == LoyaltyTransaction.Status.POSTED)
                .map(LoyaltyTransaction::getPointsDelta)
                .filter(d -> d.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(stampedPointsIssued)
                .as("sum over the stamped rows equals the invoice's own pointsIssued")
                .isEqualByComparingTo(invoice.getPointsIssued());
    }

    @Test
    void aLaterInvoiceNeverStealsRowsAnEarlierOneAlreadyBilled() {
        earn(100, "earn-claim-once");
        issueVoucher();   // both invoices need a non-zero total to be raised
        Merchant m = merchantRepository.findById(merchantId).orElseThrow();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Invoice first = invoicingService.generate(m, today.minusDays(1), today).orElseThrow();

        // A wider, overlapping period — which is reachable when a merchant's
        // billing cycle changes. The claim-once guard means the earlier invoice
        // keeps the rows it billed; re-stamping would silently rewrite history.
        Invoice second = invoicingService.generate(m, today.minusDays(5), today.plusDays(1)).orElseThrow();
        assertThat(second.getId()).isNotEqualTo(first.getId());

        assertThat(transactions.findAll().stream()
                .filter(t -> merchantId.equals(t.getMerchantId()))
                .map(LoyaltyTransaction::getInvoiceId))
                .as("rows stay attributed to the invoice that billed them first")
                .containsOnly(first.getId());
    }

    /**
     * Issue one voucher so the period has a non-zero fee total and an invoice is
     * actually raised. Invoices are priced off voucher activity, not points, so
     * a points-only period yields no invoice at all — see
     * {@link #pointsAloneRaiseNoInvoice_soTheirRowsStayUnlinked}.
     */
    private void issueVoucher() {
        VoucherTemplate tpl = voucherTemplateService.create(tenantId, merchantId,
                new Dtos.VoucherTemplateRequest(null, "Invoice link tpl",
                        VoucherTemplate.VoucherType.SINGLE_USE,
                        VoucherTemplate.ValueType.PERCENT,
                        "USD", null, 1, 30, null));
        voucherService.issue(tenantId,
                new Dtos.IssueVoucherRequest(null, tpl.getId(), new BigDecimal("10"),
                        null, null, userId,
                        Voucher.DeliveryChannel.NONE, null, null, null));
    }

    private void earn(int amount, String ref) {
        // CHECKOUT_S2S bypasses the V32 staff-typed earn guards (self-block /
        // require-reference), which aren't what this test is about.
        transactionService.post(tenantId, merchantId,
                new Dtos.TransactionRequest(null, userId, null, TransactionType.PURCHASE,
                        new BigDecimal(amount), "USD", ref),
                com.innbucks.loyaltyservice.entity.EarnChannel.CHECKOUT_S2S);
    }
}
