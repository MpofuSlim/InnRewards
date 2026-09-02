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

    @Test
    void sumRedeemedValueByMerchantId_sumsBaseValueNotFaceValue() throws Exception {
        String jpql = queryOf("sumRedeemedValueByMerchantId", java.util.UUID.class);

        assertThat(jpql).contains("SUM(v.baseValue)");
        assertThat(jpql)
                .as("summing v.value would add ZWG to USD in a mixed-currency scope")
                .doesNotContain("SUM(v.value)");
    }
}
