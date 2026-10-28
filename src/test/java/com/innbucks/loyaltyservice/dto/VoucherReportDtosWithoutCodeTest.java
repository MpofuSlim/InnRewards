package com.innbucks.loyaltyservice.dto;

import com.innbucks.loyaltyservice.dto.VoucherReportDtos.VoucherDetail;
import com.innbucks.loyaltyservice.dto.VoucherReportDtos.VoucherReport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The code-less copies read-only platform access is served. Each is rebuilt
 * POSITIONALLY from a long record (39 components on {@link VoucherDetail}, with
 * long runs of same-typed neighbours), so a swap compiles and no compiler can
 * catch it — the symptom would be one person's phone in another's column. So
 * every component gets a DISTINCT value, and every one but the code must come
 * back unchanged.
 */
class VoucherReportDtosWithoutCodeTest {

    @Test
    void voucherDetail_withoutCode_changesTheCodeAndNothingElse() throws Exception {
        VoucherDetail original = distinct(VoucherDetail.class);
        assertThat(original.code()).isNotNull();

        assertOnlyChanged(original, original.withoutCode(), "code");
    }

    @Test
    void fraudAttempt_withoutCode_changesTheCodeAndNothingElse() throws Exception {
        Dtos.FraudAttemptResponse original = distinct(Dtos.FraudAttemptResponse.class);
        assertThat(original.voucherCode()).isNotNull();

        assertOnlyChanged(original, original.withoutCode(), "voucherCode");
    }

    @Test
    void voucherReport_withoutCodes_stripsEveryRowAndKeepsThePage() throws Exception {
        VoucherDetail a = distinct(VoucherDetail.class);
        VoucherDetail b = distinct(VoucherDetail.class, 1000);
        PageResponse<VoucherDetail> page = PageResponse.<VoucherDetail>builder()
                .content(List.of(a, b)).page(2).size(2).totalElements(9).totalPages(5)
                .first(false).last(false).build();
        VoucherReportDtos.VoucherSummary summary = new VoucherReportDtos.VoucherSummary(
                9, Map.of("ISSUED", 9L), Map.of("ISSUED", BigDecimal.TEN), BigDecimal.TEN, 0,
                BigDecimal.ZERO, 9, 0, 0, 0.0);
        VoucherReport report = new VoucherReport("TENANT", UUID.randomUUID(), "Simbisa",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), summary, page);

        VoucherReport stripped = report.withoutCodes();

        assertThat(stripped.vouchers().getContent()).extracting(VoucherDetail::code).containsOnlyNulls();
        assertThat(stripped.vouchers().getContent()).containsExactly(a.withoutCode(), b.withoutCode());
        assertThat(stripped.vouchers())
                .usingRecursiveComparison().ignoringFields("content").isEqualTo(page);
        assertThat(stripped.level()).isEqualTo(report.level());
        assertThat(stripped.scopeId()).isEqualTo(report.scopeId());
        assertThat(stripped.scopeName()).isEqualTo(report.scopeName());
        assertThat(stripped.from()).isEqualTo(report.from());
        assertThat(stripped.to()).isEqualTo(report.to());
        assertThat(stripped.summary()).isSameAs(summary);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void assertOnlyChanged(Record before, Record after, String stripped) throws Exception {
        for (RecordComponent c : before.getClass().getRecordComponents()) {
            Object was = c.getAccessor().invoke(before);
            Object is = c.getAccessor().invoke(after);
            if (c.getName().equals(stripped)) {
                assertThat(is).as(c.getName()).isNull();
            } else {
                assertThat(is).as(c.getName()).isEqualTo(was);
            }
        }
    }

    private static <T extends Record> T distinct(Class<T> type) throws Exception {
        return distinct(type, 0);
    }

    /** One instance whose components are all different from one another. */
    private static <T extends Record> T distinct(Class<T> type, int seed) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = valueFor(types[i], seed + i + 1);
        }
        Constructor<T> canonical = type.getDeclaredConstructor(types);
        return canonical.newInstance(values);
    }

    private static Object valueFor(Class<?> t, int n) {
        if (t == String.class) return "s" + n;
        if (t == UUID.class) return new UUID(0, n);
        if (t == BigDecimal.class) return BigDecimal.valueOf(n);
        if (t == Instant.class) return Instant.ofEpochSecond(1_700_000_000L + n);
        if (t == int.class) return n;
        if (t == long.class) return (long) n;
        if (t == boolean.class) return n % 2 == 0;
        if (t == List.class) return List.of("l" + n);
        throw new IllegalStateException("no distinct value for " + t);
    }
}
