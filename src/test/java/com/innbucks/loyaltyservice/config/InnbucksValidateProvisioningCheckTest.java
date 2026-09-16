package com.innbucks.loyaltyservice.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The boot check for the InnBucks backlog validate sweep. Its whole job is to
 * SHOUT (an ERROR at boot) when the sweep is switched on but cannot run — the
 * ZimSwitch "flag on in one config source, credentials in the other" lesson —
 * and to distinguish the two ways that happens, because they send an operator
 * hunting different things:
 * <ul>
 *   <li>no credentials → HALF-PROVISIONED (provision BANK_API_* / overrides);</li>
 *   <li>a validate-path with no {msisdn} placeholder → MISCONFIGURED (the
 *       fail-open guard; restore the placeholder).</li>
 * </ul>
 * These assert on the emitted level + which message, because "does it shout the
 * RIGHT thing" is the entire behaviour.
 */
class InnbucksValidateProvisioningCheckTest {

    private static final String GOOD_PATH = "/auth/client-service/msisdn/{msisdn}/validate";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(InnbucksValidateProvisioningCheck.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private void run(boolean sweepEnabled, String validatePath, boolean clientConfigured) {
        InnbucksCustomerValidateClient client = mock(InnbucksCustomerValidateClient.class);
        when(client.isConfigured()).thenReturn(clientConfigured);
        new InnbucksValidateProvisioningCheck(sweepEnabled, validatePath, client)
                .checkSweepProvisioning();
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    @Test
    @DisplayName("says nothing when the sweep is off — the production default")
    void disabled_isSilent() {
        run(false, GOOD_PATH, false);
        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("enabled + no credentials → HALF-PROVISIONED error naming the BANK_API_* vars")
    void enabledButUnconfigured_isHalfProvisionedError() {
        run(true, GOOD_PATH, false);

        assertThat(events()).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("HALF-PROVISIONED").contains("BANK_API_");
        });
    }

    @Test
    @DisplayName("enabled + a placeholder-less validate-path → MISCONFIGURED error, not the credentials one")
    void enabledWithBadPath_isMisconfiguredError() {
        // isConfigured() is false BECAUSE the path lacks {msisdn}; the check must
        // name that specific fail-open, not send the operator after credentials.
        run(true, "/auth/client-service/validate", false);

        assertThat(events()).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage())
                    .contains("MISCONFIGURED").contains("{msisdn}");
            assertThat(e.getFormattedMessage()).doesNotContain("HALF-PROVISIONED");
        });
    }

    @Test
    @DisplayName("enabled + fully configured → a single WARN that the sweep is live")
    void enabledAndConfigured_warnsItIsLive() {
        run(true, GOOD_PATH, true);

        assertThat(events()).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("ENABLED");
        });
    }
}
