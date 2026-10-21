package com.innbucks.loyaltyservice.repository;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the multi-currency reporting contract (design PR 5) at the level a
 * sandbox without Docker can actually check: the two money aggregations over
 * {@code Voucher} must sum the USD-normalised {@code baseValue}, never the
 * raw {@code value}.
 *
 * <p>Why this is worth a test rather than a comment: summing {@code value}
 * across a scope that mixes currencies adds ZWG to USD and produces a figure
 * that is not money in any currency — and it silently keeps working, returning
 * a plausible number, which is exactly the kind of regression review misses.
 * A future edit that "simplifies" the JPQL back to {@code SUM(v.value)} fails
 * here.
 *
 * <p>The behaviour of the queries themselves (grouping, filters, NULL
 * exclusion) is covered by the container-backed reporting tests in CI; this
 * pins only the column choice.
 */
class VoucherMoneySumUnitTest {

    private static String queryOf(String methodName, Class<?>... params) throws Exception {
        Method m = VoucherRepository.class.getMethod(methodName, params);
        org.springframework.data.jpa.repository.Query q =
                m.getAnnotation(org.springframework.data.jpa.repository.Query.class);
        assertThat(q).as("@Query on %s", methodName).isNotNull();
        return q.value();
    }

    @Test
    void reportSummaryByStatus_sumsBaseValueNotFaceValue() throws Exception {
        String jpql = queryOf("reportSummaryByStatus",
                java.util.UUID.class, java.util.UUID.class, java.util.UUID.class,
                java.util.UUID.class, java.time.Instant.class, java.time.Instant.class);

        assertThat(jpql).contains("SUM(v.baseValue)");
        assertThat(jpql)
                .as("summing v.value would add ZWG to USD in a mixed-currency scope")
                .doesNotContain("SUM(v.value)");
    }

    /**
     * Since V61 the redeemed value is summed over USES (voucher_redemptions), so
     * a part-used balance voucher counts what was actually used. The same rule
     * holds one table over: the USD {@code baseAmount}, never the local
     * {@code amount}.
     */
    @Test
    void sumRedeemedValueByMerchantId_sumsTheUsesBaseAmountNotTheirLocalAmount() throws Exception {
        String jpql = queryOf("sumRedeemedValueByMerchantId", java.util.UUID.class);

        assertThat(jpql).contains("SUM(r.baseAmount)");
        assertThat(jpql)
                .as("summing r.amount would add ZWG to USD in a mixed-currency scope")
                .doesNotContain("SUM(r.amount)");
        assertThat(jpql).as("only successful uses moved money").contains("Result.SUCCESS");
    }

    @Test
    void sumRedeemedValueByMerchantIds_sumsTheUsesBaseAmountToo() throws Exception {
        String jpql = queryOf("sumRedeemedValueByMerchantIds", java.util.Collection.class);

        assertThat(jpql).contains("SUM(r.baseAmount)").doesNotContain("SUM(r.amount)").contains("Result.SUCCESS");
    }
}
