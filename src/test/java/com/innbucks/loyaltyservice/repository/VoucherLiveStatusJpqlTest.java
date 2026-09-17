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
 * Keeps the two hand-spelled JPQL status lists in step with
 * {@link Voucher#LIVE_STATUSES}.
 *
 * <p>V48 replaced six copy-pasted "live voucher" lists with that one constant —
 * the duplication was the reason retiring a single status touched twelve files.
 * Two sites could not be converted: a JPQL string cannot reference a Java
 * constant, so both carry a change-together comment instead. A comment is not a
 * guarantee, and the failure mode is unusually unhelpful: Spring Data validates
 * a declared {@code @Query} when the repository bean is created, so a stale
 * fully-qualified constant is a context-load failure at BOOT — and in this repo
 * that is only reachable from a {@code @SpringBootTest}, all of which need
 * Docker and therefore only run in CI. A list left merely *wrong* rather than
 * unparseable is worse again: it boots fine and silently returns the wrong
 * rows in a customer's wallet count and in the expiry-warning sweep.
 *
 * <p>So this asserts the agreement directly off the annotations, with no Spring
 * context and no database. It fails on the same commit that changes
 * {@code LIVE_STATUSES} without updating both queries.
 */
class VoucherLiveStatusJpqlTest {

    /** Matches the fully-qualified constants the two queries use. */
    private static final Pattern STATUS_REF =
            Pattern.compile("Voucher\\.Status\\.([A-Z_]+)");

    private static List<String> statusesNamedIn(String methodName, Class<?>... paramTypes) {
        Method method;
        try {
            method = VoucherRepository.class.getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "VoucherRepository." + methodName + " was renamed or removed; this test names "
                            + "it because its @Query spells out LIVE_STATUSES by hand", e);
        }
        Query query = method.getAnnotation(Query.class);
        assertThat(query)
                .as("%s must still carry a declared @Query — if it became a derived query "
                        + "or a Specification, the hand-spelled list is gone and so is this test's reason "
                        + "to exist", methodName)
                .isNotNull();

        List<String> names = new ArrayList<>();
        Matcher m = STATUS_REF.matcher(query.value());
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
        assertThat(statusesNamedIn("countActiveGroupedByUserId", List.class))
                .containsExactlyInAnyOrderElementsOf(liveStatusNames());
    }

    @Test
    void theExpiryWarningQueryNamesExactlyTheLiveStatuses() {
        assertThat(statusesNamedIn("findExpiringForWarning",
                java.time.Instant.class, java.time.Instant.class,
                org.springframework.data.domain.Pageable.class))
                .containsExactlyInAnyOrderElementsOf(liveStatusNames());
    }

    @Test
    void everyStatusNamedInJpqlIsStillAConstantOnTheEnum() {
        // The boot-failure case, caught here instead: a name that no longer
        // exists on the enum makes Spring Data reject the query when the
        // repository bean is created, which in this repo only surfaces in CI.
        List<String> named = new ArrayList<>(statusesNamedIn("countActiveGroupedByUserId", List.class));
        named.addAll(statusesNamedIn("findExpiringForWarning",
                java.time.Instant.class, java.time.Instant.class,
                org.springframework.data.domain.Pageable.class));
        assertThat(named).isNotEmpty();
        for (String name : named) {
            // Throws IllegalArgumentException, failing the test, if the constant is gone.
            assertThat(Voucher.Status.valueOf(name)).isNotNull();
        }
    }
}
