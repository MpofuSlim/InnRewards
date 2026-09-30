package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Voucher;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

/**
 * Voucher-report aggregates over an arbitrary filter. The fixed JPQL summary
 * ({@code reportSummaryByStatus}) cannot express the report's optional filters
 * (payment type, currency, search, dates…), so the summary for a filtered
 * report is built from the SAME {@link Specification} as its rows — the counts
 * and the table can never disagree about what is in scope.
 */
public interface VoucherReportQueries {

    /** {@code [status, count, sum(baseValue)]} per status, for every voucher the
     *  specification admits. Same row shape as {@code reportSummaryByStatus}. */
    List<Object[]> summaryByStatus(Specification<Voucher> spec);
}
