package com.innbucks.loyaltyservice.service.partner;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The partner channels are spelled out twice: in {@link PartnerVoucherService#CHANNELS}, which
 * decides what a request may send, and in the CHECKs on the order and the pay-at-redeem attempt.
 * A channel the service accepts and a CHECK refuses fails the INSERT after every rule has
 * passed, which a client reads as a server error. This reads the NEWEST migration defining each
 * CHECK and requires it to name exactly the service's channels, so a new channel needs a
 * migration in the same change (V71 added API).
 */
class PartnerChannelCheckConstraintTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @ParameterizedTest
    @ValueSource(strings = {"chk_vpo_channel", "chk_vrp_channel"})
    void theNewestCheckNamesExactlyTheServicesChannels(String constraint) throws IOException {
        Pattern definition = Pattern.compile(
                "CONSTRAINT\\s+" + constraint + "\\s+CHECK\\s*\\([^;]*?\\bIN\\s*\\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE);
        Path newest;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            newest = files
                    .filter(p -> VERSION.matcher(p.getFileName().toString()).matches())
                    .filter(p -> definition.matcher(read(p)).find())
                    .max(Comparator.comparingInt(PartnerChannelCheckConstraintTest::version))
                    .orElseThrow(() -> new AssertionError("no migration defines " + constraint));
        }

        Matcher m = definition.matcher(read(newest));
        m.find();
        List<String> named = QUOTED.matcher(m.group(1)).results().map(r -> r.group(1)).toList();

        assertThat(named)
                .as("%s defines the newest %s. A channel the service accepts and the CHECK does not "
                        + "name fails every sale or payment on it. Add a NEW migration that widens the "
                        + "CHECK; never edit an applied one.", newest.getFileName(), constraint)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(PartnerVoucherService.CHANNELS);
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
