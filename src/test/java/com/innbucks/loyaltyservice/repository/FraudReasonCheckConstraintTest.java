package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.FraudAttempt;
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
 * {@code fraud_attempts.reason} is {@code @Enumerated(STRING)} behind a CHECK
 * that spells the enum out by hand in SQL. V17 wrote that list; four values were
 * added to {@link FraudAttempt.Reason} afterwards without widening it, and every
 * refusal recording one of them failed its evidence INSERT and surfaced as a 409
 * instead of the refusal the endpoint documents — until V52.
 *
 * <p>Nothing else fails when that drifts: the Docker-backed suite only hits the
 * CHECK on a path that records the new reason, and nothing at compile or boot
 * time compares the two lists. This reads the NEWEST migration that defines the
 * constraint and requires it to name exactly the enum's values. Adding a reason
 * therefore needs a new migration widening the CHECK, in the same PR.
 */
class FraudReasonCheckConstraintTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final Pattern CONSTRAINT = Pattern.compile(
            "ADD\\s+CONSTRAINT\\s+chk_fraud_attempts_reason\\s+CHECK\\s*\\(\\s*reason\\s+IN\\s*\\(([^)]*)\\)",
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
                    .max(Comparator.comparingInt(FraudReasonCheckConstraintTest::version))
                    .orElseThrow(() -> new AssertionError("no migration defines chk_fraud_attempts_reason"));
        }

        Matcher m = CONSTRAINT.matcher(read(newest));
        m.find();
        List<String> named = QUOTED.matcher(m.group(1)).results().map(r -> r.group(1)).toList();
        Set<String> enumValues = Arrays.stream(FraudAttempt.Reason.values())
                .map(Enum::name).collect(Collectors.toSet());

        assertThat(named)
                .as("%s defines the newest chk_fraud_attempts_reason. A FraudAttempt.Reason the "
                        + "CHECK does not name makes FraudService.record fail for that reason, and the "
                        + "refusal it was recording becomes a 409. Add a NEW migration that widens the "
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
