package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bulk issue against REAL Postgres, measured with Hibernate statistics: the
 * number of queries it runs must not grow with the quantity. It used to run a
 * {@code findByCode} (plus a rule and an FX lookup) per voucher, and each one
 * auto-flushed every voucher created so far — quadratic in the quantity.
 */
class BulkVoucherIssueIT extends PostgresIntegrationTestBase {

    @Autowired TenantRepository tenants;
    @Autowired MerchantRepository merchants;
    @Autowired VoucherRepository vouchers;
    @Autowired VoucherService voucherService;
    @Autowired EntityManagerFactory emf;

    @MockitoBean UserServiceClient userServiceClient;

    private UUID tenantId;
    private UUID merchantId;
    private Statistics stats;

    @BeforeEach
    void seed() {
        Tenant t = new Tenant();
        t.setCode("bvi-" + UUID.randomUUID().toString().substring(0, 8));
        t.setName("Bulk Voucher Issue");
        tenantId = tenants.save(t).getId();
        Merchant m = new Merchant();
        m.setTenantId(tenantId);
        m.setName("Bulk Fuel");
        m.setCurrency("USD");
        merchantId = merchants.save(m).getId();
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
    }

    @AfterEach
    void stopCounting() {
        stats.setStatisticsEnabled(false);
    }

    private Dtos.BulkIssueRequest bulk(int quantity) {
        return new Dtos.BulkIssueRequest(merchantId, null, new BigDecimal("5.00"), "USD", null,
                quantity, "bulk-it", null);
    }

    /** Queries run (and statements prepared) by one bulk issue of {@code quantity}. */
    private long[] measure(int quantity) {
        stats.clear();
        List<Dtos.VoucherResponse> out = voucherService.issueBulk(tenantId, bulk(quantity));
        assertThat(out).hasSize(quantity);
        assertThat(stats.getEntityInsertCount()).isEqualTo(quantity + 1L);   // + the batch row
        return new long[]{stats.getQueryExecutionCount(), stats.getPrepareStatementCount()};
    }

    @Test
    void theQueryCountDoesNotGrowWithTheQuantity() {
        long[] small = measure(5);
        long[] large = measure(400);

        assertThat(large[0])
                .as("queries for 400 vouchers vs 5")
                .isEqualTo(small[0]);
        // Prepared statements grow only by the extra JDBC insert batches
        // (batch_size 50): never one per voucher.
        assertThat(large[1] - small[1])
                .as("extra statements for 395 more vouchers")
                .isLessThanOrEqualTo(400 / 50 + 1);
    }

    @Test
    void theCapIsEnforced_andTheCapItselfIsIssuable() {
        assertThatThrownBy(() -> voucherService.issueBulk(tenantId, bulk(1001)))
                .isInstanceOf(LoyaltyException.class)
                .hasFieldOrPropertyWithValue("code", "BULK_QUANTITY_TOO_LARGE");

        List<Dtos.VoucherResponse> out = voucherService.issueBulk(tenantId, bulk(1000));
        assertThat(out).hasSize(1000);
        assertThat(out.stream().map(Dtos.VoucherResponse::code).distinct()).hasSize(1000);
        assertThat(vouchers.findExistingCodes(out.stream().map(Dtos.VoucherResponse::code).toList()))
                .hasSize(1000);
    }
}
