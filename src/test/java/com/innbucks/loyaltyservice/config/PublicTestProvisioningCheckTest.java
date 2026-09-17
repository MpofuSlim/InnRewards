package com.innbucks.loyaltyservice.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boot check covers two conditions the service otherwise reports nowhere:
 * <ul>
 *   <li>a tenant pin that is SET but unparseable, which
 *       {@code PublicTestController.parseUuidOrNull} swallows so it behaves
 *       identically to an unset one;</li>
 *   <li>the surface switched ON with no {@code x-api-key} provisioned, which
 *       {@code PublicTestApiKeyFilter} turns into a 503 on every call —
 *       indistinguishable from an outage unless boot says otherwise.</li>
 * </ul>
 *
 * <p>These assert on the emitted level, because "does it shout" is the entire
 * behaviour — there is no collaborator left to observe. The two conditions are
 * independent, so each group of assertions selects its own lines rather than
 * expecting the check to emit exactly one.
 */
class PublicTestProvisioningCheckTest {

    private static final String KEY = "a-provisioned-public-test-key";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(PublicTestProvisioningCheck.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private void run(boolean enabled, String pin) {
        run(enabled, pin, KEY);
    }

    private void run(boolean enabled, String pin, String apiKey) {
        new PublicTestProvisioningCheck(enabled, pin, apiKey).checkPublicTestProvisioning();
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    /** The lines about the tenant pin, ignoring whatever the key check said. */
    private List<ILoggingEvent> pinEvents() {
        return events().stream()
                .filter(e -> e.getFormattedMessage().toLowerCase().contains("tenant"))
                .toList();
    }

    /** The lines about the api-key, ignoring whatever the pin check said. */
    private List<ILoggingEvent> keyEvents() {
        return events().stream()
                .filter(e -> e.getFormattedMessage().contains("api-key")
                        || e.getFormattedMessage().contains("API_KEY"))
                .toList();
    }

    @Test
    @DisplayName("says nothing at all when the surface is off — the production case")
    void disabled_isSilent() {
        // A production cell has this off and must stay off. It should not even
        // narrate, or the line becomes noise operators learn to skip.
        run(false, "not-a-uuid");

        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("a malformed pin is an ERROR — it is silently ignored at runtime")
    void malformedPin_isAnError() {
        run(true, "0a571c1c-oops");

        assertThat(pinEvents()).singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(e.getFormattedMessage())
                            .contains("MISCONFIGURED")
                            .contains("SILENTLY IGNORED");
                });
    }

    @Test
    @DisplayName("a valid pin is INFO, not an error")
    void validPin_isInfo() {
        run(true, "0a571c1c-7c75-4000-a000-000000000001");

        assertThat(pinEvents()).singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
    }

    @Test
    @DisplayName("no pin is INFO — it is a supported configuration, not a fault")
    void noPin_isInfo() {
        // Blank used to combine with multi-tenant phones to break the points
        // writes. It no longer can, so this must not be reported as a problem.
        for (String blank : new String[]{"", "   ", null}) {
            appender.list.clear();
            run(true, blank);

            assertThat(pinEvents()).as("pin=%s", blank).singleElement()
                    .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
        }
    }

    @Test
    @DisplayName("enabled with no api-key is an ERROR — every call is 503 until it is set")
    void missingApiKey_isAnError() {
        // The half-provisioned state. From a client it looks exactly like an
        // outage, so this line is the operator's only clue it is a config gap.
        for (String blank : new String[]{"", "   ", null}) {
            appender.list.clear();
            run(true, "", blank);

            assertThat(keyEvents()).as("key=%s", blank).singleElement()
                    .satisfies(e -> {
                        assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                        assertThat(e.getFormattedMessage())
                                .contains("HALF-PROVISIONED")
                                .contains("LOYALTY_PUBLIC_TEST_API_KEY");
                    });
        }
    }

    @Test
    @DisplayName("a provisioned api-key is INFO, not an error")
    void providedApiKey_isInfo() {
        run(true, "", KEY);

        assertThat(keyEvents()).singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
    }

    @Test
    @DisplayName("says nothing about the key either when the surface is off")
    void disabled_saysNothingAboutTheKey() {
        // A production cell has the surface off and therefore needs no key —
        // reporting a missing one there would be a permanent false alarm.
        run(false, "", null);

        assertThat(events()).isEmpty();
    }
}
