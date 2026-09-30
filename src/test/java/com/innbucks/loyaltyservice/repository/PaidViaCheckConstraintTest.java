package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code voucher_purchase_orders.paid_via} is {@code @Enumerated(STRING)} behind
 * a CHECK that spells the enum out by hand. A {@link VoucherPurchaseOrder.PaidVia}
 * the CHECK does not name makes every confirmation of that method fail its UPDATE
 * AFTER the voucher is minted in the same transaction — the fraud-reason drift
 * V52 fixed, and payment-service's order-type drift its V16 fixed, both found only
 * against a real database. This reads the NEWEST migration that defines the
 * constraint and requires it to name exactly the enum's values, so adding a
 * payment method needs a migration in the same PR.
 */
class PaidViaCheckConstraintTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final Pattern CONSTRAINT = Pattern.compile(
            "CONSTRAINT\\s+chk_vpo_paid_via\\s+CHECK\\s*\\([^;]*?\\bIN\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Test
    void theNewestCheckNamesExactlyTheEnumValues() throws IOException {
        Path newest;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            newest = files
                    .filter(p -> VERSION.matcher(p.getFileName().toString()).matches())
                    .filter(p -> CONSTRAINT.matcher(read(p)).find())
                    .max(Comparator.comparingInt(PaidViaCheckConstraintTest::version))
                    .orElseThrow(() -> new AssertionError("no migration defines chk_vpo_paid_via"));
        }

        Matcher m = CONSTRAINT.matcher(read(newest));
        m.find();
        List<String> named = QUOTED.matcher(m.group(1)).results().map(r -> r.group(1)).toList();
        Set<String> enumValues = Arrays.stream(VoucherPurchaseOrder.PaidVia.values())
                .map(Enum::name).collect(Collectors.toSet());

        assertThat(named)
                .as("%s defines the newest chk_vpo_paid_via. A PaidVia the CHECK does not name "
                        + "fails every confirmation of that method. Add a NEW migration that widens the "
                        + "CHECK; never edit an applied one.", newest.getFileName())
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(enumValues);
    }

    private static int version(Path p) {
        Matcher m = VERSION.matcher(p.getFileName().toString());
        m.matches();
        return Integer.parseInt(m.group(1));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new AssertionError("could not read " + p, e);
        }
    }
}
