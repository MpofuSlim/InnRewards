package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.service.InvoicingService;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Balance vouchers (V61) against real Postgres: what the voucher row lock and
 * the {@code (merchant, reference)} partial unique index actually guarantee
 * under concurrency, which a mocked repository cannot show.
 *
 * <ul>
 *   <li>Many tills each taking a slice of ONE voucher never overspend it: the
 *       amounts applied sum to exactly the face value, the USD shares to
 *       exactly the base value, and the voucher ends REDEEMED at zero.</li>
 *   <li>A double-submitted use with the same reference lands once: one call
 *       spends, the others replay it.</li>
 *   <li>The same reference racing onto two different vouchers lands once: the
 *       other is 409 DUPLICATE_REFERENCE, with nothing spent.</li>
 *   <li>The index (SUCCESS rows only, a NULL reference never collides), the
 *       balance CHECK, and an invoice billing every use in its period.</li>
 * </ul>
 */
class VoucherBalancePostgresIT extends PostgresIntegrationTestBase {

    @Autowired TenantRepository tenantRepository;
    @Autowired MerchantService merchantService;
    @Autowired MerchantRepository merchantRepository;
    @Autowired UserService userService;
    @Autowired VoucherService voucherService;
    @Autowired VoucherRepository voucherRepository;
    @Autowired InvoicingService invoicingService;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean UserServiceClient userServiceClient;

    private UUID tenantId;
    private UUID merchantId;
    private UUID userId;

    @BeforeEach
    void seed() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));
        Tenant t = new Tenant();
        t.setCode("bal-" + System.nanoTime());
        t.setName("Balance Voucher Test");
        tenantId = tenantRepository.save(t).getId();
        merchantId = merchantService.create(tenantId,
                new Dtos.MerchantRequest("Balance Cafe", "F&B", "USD", Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
        LoyaltyUser u = userService.findOrEnrol(tenantId, "+26377" + (1000000 + (System.nanoTime() % 8999999)),
                merchantId);
        userId = u.getId();
    }

    private Dtos.VoucherResponse issue(UUID merchant, String face) {
        return voucherService.issue(tenantId, new Dtos.IssueVoucherRequest(merchant, null,
                new BigDecimal(face), "USD", null, null, null, userId, Voucher.DeliveryChannel.NONE, null));
    }

    private Dtos.RedeemVoucherRequest use(String code, String amount, String reference) {
        return new Dtos.RedeemVoucherRequest(null, code, userId, "TILL", "dev", "127.0.0.1",
                amount == null ? null : new BigDecimal(amount), reference);
    }

    /** Runs every task at the same instant on its own thread (no security context: an unauthenticated till). */
    private static <T> List<Object> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).as("a worker hung — a lock-up").isTrue();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : futures) out.add(f.get());
        return out;
    }

    @Test
    void manyTillsTakingSlicesOfOneVoucher_neverOverspendIt() throws Exception {
        Dtos.VoucherResponse v = issue(merchantId, "50.00");
        // Ten tills each asking for 7.00 of a 50.00 voucher: 70.00 of demand.
        // Seven get their 7.00, one gets the 1.00 that is left, two are refused.
        List<Callable<Dtos.RedemptionResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> voucherService.redeemIdempotent(tenantId, merchantId, use(v.code(), "7.00", null)));
        }

        List<Object> results = race(tasks);

        BigDecimal applied = BigDecimal.ZERO;
        int successes = 0;
        int spent = 0;
        List<Object> unexpected = new ArrayList<>();
        for (Object r : results) {
            if (r instanceof Dtos.RedemptionResponse ok) {
                successes++;
                applied = applied.add(ok.value());
            } else if (r instanceof LoyaltyException e && "ALREADY_REDEEMED".equals(e.getCode())) {
                spent++;
            } else {
                unexpected.add(r);
            }
        }
        assertThat(unexpected).isEmpty();
        assertThat(applied).as("the sum of what was applied is the face value, exactly").isEqualByComparingTo("50");
        assertThat(successes).isEqualTo(8);
        assertThat(spent).isEqualTo(2);

        Voucher after = voucherRepository.findById(v.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Voucher.Status.REDEEMED);
        assertThat(after.getRemainingBalance()).isEqualByComparingTo("0");
        assertThat(after.getRedeemedAt()).isNotNull();

        BigDecimal baseSum = jdbc.queryForObject(
                "SELECT SUM(base_amount) FROM voucher_redemptions WHERE voucher_id = ? AND result = 'SUCCESS'",
                BigDecimal.class, v.id());
        assertThat(baseSum).isEqualByComparingTo(after.getBaseValue());
        Integer firsts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM voucher_redemptions WHERE voucher_id = ? AND first_use", Integer.class, v.id());
        assertThat(firsts).as("exactly one first use, whoever won the lock first").isEqualTo(1);
    }

    @Test
    void aDoubleSubmittedUse_landsOnce_andTheOtherCallsReplayIt() throws Exception {
        Dtos.VoucherResponse v = issue(merchantId, "50.00");
        Callable<Dtos.RedemptionResponse> sameUse =
                () -> voucherService.redeemIdempotent(tenantId, merchantId, use(v.code(), "20.00", "RCPT-DUP"));

        List<Object> results = race(List.of(sameUse, sameUse, sameUse));

        assertThat(results).allMatch(r -> r instanceof Dtos.RedemptionResponse);
        List<Dtos.RedemptionResponse> ok = results.stream().map(r -> (Dtos.RedemptionResponse) r).toList();
        assertThat(ok).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(ok).extracting(Dtos.RedemptionResponse::redemptionId).containsOnly(ok.get(0).redemptionId());
        assertThat(ok).allSatisfy(r -> assertThat(r.remainingBalance()).isEqualByComparingTo("30"));
        assertThat(voucherRepository.findById(v.id()).orElseThrow().getRemainingBalance())
                .isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_redemptions WHERE voucher_id = ? "
                + "AND result = 'SUCCESS'", Integer.class, v.id())).isEqualTo(1);
    }

    @Test
    void theSameReferenceRacingOntoTwoVouchers_landsOnce_andTheOtherIsDuplicateReference() throws Exception {
        Dtos.VoucherResponse a = issue(merchantId, "10.00");
        Dtos.VoucherResponse b = issue(merchantId, "10.00");

        List<Object> results = race(List.of(
                () -> voucherService.redeemIdempotent(tenantId, merchantId, use(a.code(), "4.00", "RCPT-X")),
                () -> voucherService.redeemIdempotent(tenantId, merchantId, use(b.code(), "4.00", "RCPT-X"))));

        assertThat(results).filteredOn(r -> r instanceof Dtos.RedemptionResponse).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof LoyaltyException e
                && "DUPLICATE_REFERENCE".equals(e.getCode())).hasSize(1);
        BigDecimal left = voucherRepository.findById(a.id()).orElseThrow().getRemainingBalance()
                .add(voucherRepository.findById(b.id()).orElseThrow().getRemainingBalance());
        assertThat(left).as("only one voucher was spent from").isEqualByComparingTo("16");
    }

    @Test
    void theReferenceIndex_coversSuccessfulUsesOnly_andNeverANullReference() {
        Dtos.VoucherResponse v = issue(merchantId, "10.00");
        String insert = "INSERT INTO voucher_redemptions (id, tenant_id, voucher_id, merchant_id, result, reference) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        jdbc.update(insert, UUID.randomUUID(), tenantId, v.id(), merchantId, "SUCCESS", "IDX-1");
        // A refusal never consumes a reference, and two refusals never collide.
        jdbc.update(insert, UUID.randomUUID(), tenantId, v.id(), merchantId, "REJECTED", "IDX-1");
        jdbc.update(insert, UUID.randomUUID(), tenantId, v.id(), merchantId, "REJECTED", "IDX-1");
        // Uses sent without a reference have nothing to collide on.
        jdbc.update(insert, UUID.randomUUID(), tenantId, v.id(), merchantId, "SUCCESS", null);
        jdbc.update(insert, UUID.randomUUID(), tenantId, v.id(), merchantId, "SUCCESS", null);

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), tenantId, v.id(), merchantId,
                "SUCCESS", "IDX-1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_voucher_redemption_merchant_reference");
    }

    @Test
    void theBalanceCheck_refusesANegativeOrOverFaceBalance() {
        Dtos.VoucherResponse v = issue(merchantId, "10.00");

        assertThatThrownBy(() -> jdbc.update("UPDATE vouchers SET remaining_balance = -1 WHERE id = ?", v.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_vouchers_remaining_balance");
        assertThatThrownBy(() -> jdbc.update("UPDATE vouchers SET remaining_balance = 10.01 WHERE id = ?", v.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_vouchers_remaining_balance");
    }

    @Test
    void theInvoiceBillsEveryUseInThePeriod_fixedOnceAndThePercentageOnEachAmount() {
        // Its own merchant, with a redeem side of FIXED 0.10 + 2%.
        UUID billed = merchantService.create(tenantId,
                new Dtos.MerchantRequest("Billed Cafe", "F&B", "USD", Merchant.BillingCycle.DAILY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED_PLUS_PERCENTAGE, new BigDecimal("0.10"),
                                new BigDecimal("2")))).id();
        Dtos.VoucherResponse v = issue(billed, "50.00");
        voucherService.redeemIdempotent(tenantId, billed, use(v.code(), "20.00", null));
        voucherService.redeemIdempotent(tenantId, billed, use(v.code(), "30.00", null));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var invoice = invoicingService.generate(merchantRepository.findById(billed).orElseThrow(), today, today)
                .orElseThrow();

        assertThat(invoice.getVouchersIssued()).isEqualTo(1);
        assertThat(invoice.getVouchersRedeemed()).as("two uses of one voucher are two").isEqualTo(2);
        // issue 0.05 + first use (0.10 + 2% of 20) + second use (2% of 30)
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("1.15");
    }

    @Test
    void aVoucherIsIssuedWithItsFaceValueAsItsBalance() {
        Dtos.VoucherResponse v = issue(merchantId, "12.34");

        assertThat(v.remainingBalance()).isEqualByComparingTo("12.34");
        assertThat(voucherRepository.findById(v.id()).orElseThrow().getRemainingBalance())
                .isEqualByComparingTo("12.34");
    }
}
