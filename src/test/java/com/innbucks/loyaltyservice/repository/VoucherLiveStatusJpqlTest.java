package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Voucher;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the hand-spelled JPQL status lists in step with
 * {@link Voucher#LIVE_STATUSES}.
 *
 * <p>V48 replaced six copy-pasted "live voucher" lists with that one constant —
 * the duplication was the reason retiring a single status touched twelve files.
 * A JPQL string cannot reference a Java constant, so the queries below carry a
 * change-together comment instead. A comment is not a guarantee, and the failure
 * mode is unusually unhelpful: Spring Data validates a declared {@code @Query}
 * when the repository bean is created, so a stale fully-qualified constant is a
 * context-load failure at BOOT — and in this repo that is only reachable from a
 * {@code @SpringBootTest}, all of which need Docker and therefore only run in
 * CI. A list left merely *wrong* rather than unparseable is worse again: it
 * boots fine and silently returns the wrong rows in a customer's wallet count,
 * in the expiry-warning sweep, or in the expired-on-redeem flip.
 *
 * <p>So this asserts the agreement directly off the annotations, with no Spring
 * context and no database. It fails on the same commit that changes
 * {@code LIVE_STATUSES} without updating every query.
 */
class VoucherLiveStatusJpqlTest {

    /** Matches the fully-qualified constants the queries use. */
    private static final Pattern STATUS_REF =
            Pattern.compile("Voucher\\.Status\\.([A-Z_]+)");

    /**
     * Every query is anchored on {@code v.status IN (} rather than scanned
     * whole, because {@code markExpiredIfDue} also names a status in its SET
     * clause (EXPIRED — the value it moves the row TO, which is deliberately not
     * a live status). Reading only the membership test keeps all three
     * assertions identical.
     */
    private static final String ANCHOR = "v.status IN (";

    private static Method repositoryMethod(String name, Class<?>... paramTypes) {
        try {
            return VoucherRepository.class.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "VoucherRepository." + name + " was renamed or removed; this test names it "
                            + "because its @Query spells out LIVE_STATUSES by hand", e);
        }
    }

    private static List<String> liveStatusesNamedIn(String methodName, Class<?>... paramTypes) {
        Method method = repositoryMethod(methodName, paramTypes);
        Query query = method.getAnnotation(Query.class);
        assertThat(query)
                .as("%s must still carry a declared @Query — if it became a derived query or a "
                        + "Specification, the hand-spelled list is gone and so is this test's "
                        + "reason to exist", methodName)
                .isNotNull();

        String jpql = query.value();
        int start = jpql.indexOf(ANCHOR);
        assertThat(start)
                .as("%s must still test status membership with '%s'; if the query was reshaped, "
                        + "re-anchor this test rather than deleting it", methodName, ANCHOR)
                .isNotNegative();
        int end = jpql.indexOf(')', start);
        assertThat(end).as("unterminated status IN list in %s", methodName).isNotNegative();

        List<String> names = new ArrayList<>();
        Matcher m = STATUS_REF.matcher(jpql.substring(start, end));
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static List<String> liveStatusNames() {
        return Voucher.LIVE_STATUSES.stream().map(Enum::name).toList();
    }

    @Test
    void theWalletCountQueryNamesExactlyTheLiveStatuses() {
        assertThat(liveStatusesNamedIn("countActiveGroupedByUserId", List.class))
                .containsExactlyInAnyOrderElementsOf(liveStatusNames());
    }

    @Test
    void theExpiryWarningQueryNamesExactlyTheLiveStatuses() {
        assertThat(liveStatusesNamedIn("findExpiringForWarning",
                java.time.Instant.class, java.time.Instant.class,
                org.springframework.data.domain.Pageable.class))
                .containsExactlyInAnyOrderElementsOf(liveStatusNames());
    }

    @Test
    void theExpiredOnRedeemFlipOnlyEverMovesALiveVoucher() {
        // The guard that makes the flip idempotent and safe to lose: a voucher
        // already REDEEMED or REVOKED must not be dragged to EXPIRED by a late
        // redemption attempt.
        assertThat(liveStatusesNamedIn("markExpiredIfDue", java.util.UUID.class))
                .containsExactlyInAnyOrderElementsOf(liveStatusNames());
    }

    @Test
    void everyStatusNamedInJpqlIsStillAConstantOnTheEnum() {
        // The boot-failure case, caught here instead: a name that no longer
        // exists on the enum makes Spring Data reject the query when the
        // repository bean is created, which in this repo only surfaces in CI.
        List<String> named = new ArrayList<>();
        for (Method m : VoucherRepository.class.getMethods()) {
            Query q = m.getAnnotation(Query.class);
            if (q == null) {
                continue;
            }
            Matcher matcher = STATUS_REF.matcher(q.value());
            while (matcher.find()) {
                named.add(matcher.group(1));
            }
        }
        // Scans EVERY declared query rather than the three named above, so a
        // fourth one added later is covered without touching this test.
        assertThat(named).isNotEmpty();
        for (String name : named) {
            // Throws IllegalArgumentException, failing the test, if the constant is gone.
            assertThat(Voucher.Status.valueOf(name)).isNotNull();
        }
    }
}
