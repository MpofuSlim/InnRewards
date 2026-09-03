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
 * The boot check exists for exactly one condition: a tenant pin that is SET but
 * unparseable, which {@code PublicTestController.parseUuidOrNull} swallows so it
 * behaves identically to an unset one. Nothing else about it can go wrong now
 * that a phone spanning tenants is resolved rather than refused.
 *
 * <p>These assert on the emitted level, because "does it shout" is the entire
 * behaviour — there is no collaborator left to observe.
 */
class PublicTestProvisioningCheckTest {

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
        new PublicTestProvisioningCheck(enabled, pin).checkPublicTestProvisioning();
    }

    private List<ILoggingEvent> events() {
        return appender.list;
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

        assertThat(events()).singleElement()
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

        assertThat(events()).singleElement()
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

            assertThat(events()).as("pin=%s", blank).singleElement()
                    .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
        }
    }
}
