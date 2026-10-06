package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.Voucher;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Every voucher expression the report's text filters ({@code q},
 * {@code issuedBy}, {@code phone} — {@link com.innbucks.loyaltyservice.dto.VoucherReportFilters})
 * run a {@code LIKE} over, and whether it is {@code lower()}-ed first.
 *
 * <p>Each is a contains / ends-with match ({@code '%term%'}, {@code '%tail'}),
 * which no B-tree can serve, so every one has a GIN trigram index on EXACTLY
 * this expression in {@code V59__voucher_search_trigram_indexes.sql}. The
 * filters OR several of these together; Postgres can only use the indexes
 * (a BitmapOr) when EVERY branch of the OR has one — a single unindexed branch
 * turns the whole search back into a scan of the table.
 *
 * <p>So the two must change together, and {@code VoucherSearchIndexTest} holds
 * them to it: it maps each constant to its column through the entity's
 * {@code @Column} and requires V59 to index {@code lower(col)} or {@code col}
 * accordingly, and it fails if the report builds a {@code LIKE} anywhere except
 * through {@link #like}. A new searchable column is a constant here plus an
 * index in a NEW migration.
 */
enum VoucherSearchColumn {

    ASSIGNEE_NAME("assigneeName", true),
    SENDER_NAME("senderName", true),
    ISSUER_EMAIL("issuerEmail", true),
    CODE("code", false),
    ASSIGNEE_PHONE("assigneePhone", false),
    SENDER_PHONE("senderPhone", false),
    ISSUER_PHONE("issuerPhone", false);

    /** The {@link Voucher} attribute (field) name. */
    final String attribute;
    /** True when the match runs over {@code lower(column)}. */
    final boolean lowered;

    VoucherSearchColumn(String attribute, boolean lowered) {
        this.attribute = attribute;
        this.lowered = lowered;
    }

    /** {@code [lower(]column[)] LIKE pattern ESCAPE '\'} — the pattern is already escaped. */
    Predicate like(Root<Voucher> root, CriteriaBuilder cb, String pattern) {
        Expression<String> column = root.get(attribute);
        return cb.like(lowered ? cb.lower(column) : column, pattern, '\\');
    }
}
