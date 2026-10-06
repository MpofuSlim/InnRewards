package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.Voucher;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps V59's trigram indexes on exactly the expressions the voucher report's
 * text search runs {@code LIKE} over ({@link VoucherSearchColumn}).
 *
 * <p>Why a test rather than a comment: an index on a DIFFERENT expression than
 * the query's is silently never used — {@code lower(name)} does not serve
 * {@code name}, and vice versa — and the search keeps returning the right rows,
 * just from a scan of the whole table. And because the filters OR several
 * columns, ONE unindexed branch is enough to turn every search back into that
 * scan. Nothing fails; it is only slow, on the cell with the most vouchers.
 *
 * <p>Read off the migration text, the entity's {@code @Column} names and the
 * report's source, with no Spring context and no database — the same approach
 * as {@code VoucherLiveStatusJpqlTest} / {@code VoucherLiveStatusMigrationTest}.
 */
class VoucherSearchIndexTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V59__voucher_search_trigram_indexes.sql");
    private static final Path REPORT =
            Path.of("src/main/java/com/innbucks/loyaltyservice/service/ReportingService.java");

    /** {@code ON vouchers USING gin (<expression> gin_trgm_ops)} — the expression, verbatim. */
    private static final Pattern GIN_TRGM = Pattern.compile(
            "ON\\s+vouchers\\s+USING\\s+gin\\s*\\((.+?)\\s+gin_trgm_ops\\s*\\)", Pattern.CASE_INSENSITIVE);

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new AssertionError("Could not read " + p + " — renamed? This test names it on purpose.", e);
        }
    }

    /** Strips SQL line comments, so prose in the header can never count as an index. */
    private static String sqlWithoutComments() {
        return read(MIGRATION).lines()
                .map(l -> l.contains("--") ? l.substring(0, l.indexOf("--")) : l)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static List<String> indexedExpressions() {
        List<String> out = new ArrayList<>();
        Matcher m = GIN_TRGM.matcher(sqlWithoutComments());
        while (m.find()) {
            out.add(normalise(m.group(1)));
        }
        return out;
    }

    private static String normalise(String sqlExpression) {
        return sqlExpression.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** The SQL expression Hibernate renders for one search column: its @Column name, lowered or not. */
    private static String expressionFor(VoucherSearchColumn c) {
        String column;
        try {
            Column annotation = Voucher.class.getDeclaredField(c.attribute).getAnnotation(Column.class);
            column = annotation != null && !annotation.name().isEmpty() ? annotation.name() : c.attribute;
        } catch (NoSuchFieldException e) {
            throw new AssertionError("Voucher has no field '" + c.attribute + "' — VoucherSearchColumn."
                    + c.name() + " names a renamed attribute", e);
        }
        return normalise(c.lowered ? "lower(" + column + ")" : column);
    }

    @Test
    void theMigrationCreatesTheExtensionTheIndexesNeed() {
        assertThat(sqlWithoutComments()).containsIgnoringCase("CREATE EXTENSION IF NOT EXISTS pg_trgm");
    }

    @Test
    void everySearchedExpressionHasATrigramIndexOnExactlyThatExpression() {
        List<String> indexed = indexedExpressions();
        for (VoucherSearchColumn c : VoucherSearchColumn.values()) {
            assertThat(indexed)
                    .as("VoucherSearchColumn.%s is matched with LIKE '%%…%%' as %s; V59 must index that exact "
                            + "expression with gin_trgm_ops, or the whole OR'd search scans the table",
                            c.name(), expressionFor(c))
                    .contains(expressionFor(c));
        }
    }

    @Test
    void theMigrationIndexesNothingTheSearchDoesNotUse() {
        List<String> searched = java.util.Arrays.stream(VoucherSearchColumn.values())
                .map(VoucherSearchIndexTest::expressionFor).toList();
        assertThat(indexedExpressions())
                .as("an index on an expression the search never runs costs every voucher write and serves nothing")
                .containsExactlyInAnyOrderElementsOf(searched);
    }

    @Test
    void theReportBuildsEveryLikeThroughVoucherSearchColumn() {
        // A LIKE written directly in the report would bypass the list above —
        // and with it the index. Route it through VoucherSearchColumn (and add
        // the index in a new migration) instead.
        String source = read(REPORT);
        assertThat(source)
                .as("ReportingService must not call cb.like directly; use VoucherSearchColumn.like")
                .doesNotContain("cb.like(")
                .doesNotContain(".like(root.get(");
        assertThat(source).contains("VoucherSearchColumn.");
    }
}
