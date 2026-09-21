package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.VoucherReportDtos.VoucherDetail;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ties the voucher CSV export's header to {@link VoucherDetail}.
 *
 * <p><b>Why this exists.</b> The export's Swagger describes it as "same columns
 * as VoucherDetail", but nothing enforced that: the header is a hand-written
 * string in one file and the record is a declaration in another. Adding a
 * component to the record and forgetting the header costs an operator a column
 * that silently is not there — the export still streams, still parses, and just
 * omits the field. That drift happened on the very commit that added
 * `senderName`/`senderPhone`/the transfer trio, which is why the invariant is
 * now a test rather than a comment.
 *
 * <p>Deliberately a SET comparison, not an ordered one: new columns are
 * APPENDED after {@code redemptionCount} so that a consumer parsing the old
 * export positionally keeps working, which necessarily makes the CSV order
 * differ from the record's declaration order.
 */
class VoucherCsvHeaderTest {

    /** Nested list — no sensible flat column, so it is the one allowed omission. */
    private static final String NOT_A_COLUMN = "redemptions";

    private static Set<String> headerColumns() {
        return new LinkedHashSet<>(Arrays.asList(
                ReportingService.VOUCHER_CSV_HEADER.strip().split(",")));
    }

    private static Set<String> recordComponents() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (RecordComponent c : VoucherDetail.class.getRecordComponents()) {
            if (!c.getName().equals(NOT_A_COLUMN)) {
                out.add(c.getName());
            }
        }
        return out;
    }

    @Test
    void everyVoucherDetailFieldHasAColumn() {
        Set<String> header = headerColumns();
        List<String> missing = recordComponents().stream().filter(c -> !header.contains(c)).toList();

        assertThat(missing)
                .as("VoucherDetail components with no CSV column — the export would silently drop them")
                .isEmpty();
    }

    @Test
    void everyColumnIsARealVoucherDetailField() {
        // The other direction: a column naming a field that no longer exists
        // produces a header promising data the writer cannot supply, which
        // shifts every value after it by one.
        Set<String> components = recordComponents();
        List<String> unknown = headerColumns().stream().filter(c -> !components.contains(c)).toList();

        assertThat(unknown)
                .as("CSV columns that are not VoucherDetail components")
                .isEmpty();
    }

    @Test
    void theHeaderHasNoDuplicateOrBlankColumns() {
        String[] raw = ReportingService.VOUCHER_CSV_HEADER.strip().split(",");

        assertThat(raw).doesNotContain("", " ");
        assertThat(new LinkedHashSet<>(Arrays.asList(raw)))
                .as("a duplicated column name would make the export ambiguous to a keyed parser")
                .hasSize(raw.length);
    }

    @Test
    void theNewColumnsAreAppendedAtTheEnd() {
        // The back-compat promise: a consumer that parsed the pre-change export
        // positionally must still find every old column at its old index. That
        // holds only while additions go on the END.
        String[] cols = ReportingService.VOUCHER_CSV_HEADER.strip().split(",");

        assertThat(cols[cols.length - 5]).isEqualTo("senderName");
        assertThat(cols[cols.length - 4]).isEqualTo("senderPhone");
        assertThat(cols[cols.length - 3]).isEqualTo("transferredAt");
        assertThat(cols[cols.length - 2]).isEqualTo("transferredFromUserId");
        assertThat(cols[cols.length - 1]).isEqualTo("transferredFromPhone");
        // ...and the last column of the OLD export is still where it was.
        assertThat(cols[cols.length - 6]).isEqualTo("redemptionCount");
    }
}
