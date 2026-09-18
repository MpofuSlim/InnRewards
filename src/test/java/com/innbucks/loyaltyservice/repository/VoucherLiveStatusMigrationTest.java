package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Voucher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The third hand-spelled copy of {@link Voucher#LIVE_STATUSES} — this one in
 * SQL, in {@code V49__collapse_multi_use_vouchers.sql}.
 *
 * <p>{@code VoucherLiveStatusJpqlTest} does this for the two JPQL {@code IN}
 * lists and says why: a query string cannot reference a Java constant, so a
 * change-together comment is all the coupling there is, and a comment is not a
 * guarantee. A migration is the same problem with a sharper edge — it is
 * **applied once and never re-run**, so a wrong list is not a bug that can be
 * fixed by correcting the file afterwards. It is a set of rows that were
 * silently skipped, on every cell, permanently, and nothing later will notice:
 * a live MULTI_USE voucher the UPDATE missed keeps paying out its full face
 * value on every remaining use, which is exactly what V49 exists to stop.
 *
 * <p>This asserts the agreement off the file itself. If someone adds a live
 * status to {@code LIVE_STATUSES} while V49 is still the newest migration, this
 * reddens and the fix is a NEW migration covering the new status — never an
 * edit to V49, which is applied history.
 */
class VoucherLiveStatusMigrationTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V49__collapse_multi_use_vouchers.sql");

    /**
     * Anchored on the {@code status IN (} of the uses_remaining UPDATE rather
     * than scanned whole, for the same reason as the JPQL test's anchor: the
     * file names other status values in its prose (REDEEMED, EXPIRED, REVOKED,
     * as the terminal ones it deliberately leaves alone), and a whole-file scan
     * would read those as part of the predicate.
     */
    private static final Pattern LIVE_PREDICATE = Pattern.compile(
            "AND\\s+status\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    private static String migrationSql() {
        try {
            return Files.readString(MIGRATION);
        } catch (IOException e) {
            throw new AssertionError(
                    "Could not read " + MIGRATION + ". If V49 was renamed, this test names it "
                            + "because it spells out LIVE_STATUSES by hand in SQL.", e);
        }
    }

    @Test
    void theMigrationTouchesExactlyTheLiveStatuses() {
        Matcher m = LIVE_PREDICATE.matcher(migrationSql());
        assertThat(m.find())
                .as("V49's uses_remaining UPDATE must keep its `AND status IN (...)` predicate — "
                        + "without it the update would also rewrite terminal rows' counters, "
                        + "restating history for vouchers that are already spent, expired or revoked")
                .isTrue();

        List<String> named = QUOTED.matcher(m.group(1)).results()
                .map(r -> r.group(1))
                .toList();
        List<String> live = Voucher.LIVE_STATUSES.stream().map(Enum::name).toList();

        assertThat(named)
                .as("V49 collapses every OUTSTANDING multi-use voucher to one use, so its status "
                        + "list is Voucher.LIVE_STATUSES. A status missing here is a set of live "
                        + "vouchers the migration silently skipped — and a migration runs once, so "
                        + "there is no later correction: those rows keep paying out their full face "
                        + "value on every remaining use. Adding a live status needs a NEW migration; "
                        + "never edit V49, which is applied history.")
                .containsExactlyInAnyOrderElementsOf(live);
    }

    @Test
    void theMigrationRetypesBeforeItNarrowsTheConstraint() {
        String sql = migrationSql();

        int retype = sql.indexOf("SET    voucher_type = 'SINGLE_USE'");
        int narrow = sql.indexOf("CHECK (voucher_type IS NULL OR voucher_type = 'SINGLE_USE')");

        assertThat(retype).as("V49 must still rewrite the rows").isNotNegative();
        assertThat(narrow).as("V49 must still narrow the CHECK").isNotNegative();
        assertThat(retype)
                .as("the UPDATE has to run BEFORE the CHECK narrows, or the ALTER fails on every "
                        + "row still holding MULTI_USE and the migration cannot apply at all — "
                        + "this repo's rule for removing a value from an EnumType.STRING column")
                .isLessThan(narrow);
    }
}
