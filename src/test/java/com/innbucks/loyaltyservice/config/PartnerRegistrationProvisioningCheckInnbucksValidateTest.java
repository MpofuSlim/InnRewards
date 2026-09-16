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
 * The {@code innbucks_validate} arm of the partner-registration boot check
 * (V44) — the arm added when the auth-mode allow-list widened. It must be
 * reachable (not fall through to the {@code default} assertion arm, which would
 * log the wrong missing-var name), and it must delegate provisioning to the
 * validate client. Other modes' arms are exercised elsewhere; this pins the new
 * one, since an inverted gate or a dropped {@code case} would silently mislead
 * an operator during exactly the half-provisioned rollout this check exists for.
 */
class PartnerRegistrationProvisioningCheckInnbucksValidateTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(PartnerRegistrationProvisioningCheck.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private void run(boolean enabled, String authMode, boolean clientConfigured) {
        InnbucksCustomerValidateClient client = mock(InnbucksCustomerValidateClient.class);
        when(client.isConfigured()).thenReturn(clientConfigured);
        new PartnerRegistrationProvisioningCheck(
                enabled, authMode,
                "", "", "", "", "", "", "", // key/public/veengu/innbucks paths — unused in this mode
                client).checkPartnerRegistrationProvisioning();
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    @Test
    @DisplayName("disabled → silent")
    void disabled_isSilent() {
        run(false, "innbucks_validate", true);
        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("enabled + configured → the INNBUCKS-VALIDATE eligibility WARN (arm is reached)")
    void enabledAndConfigured_warnsEligibilityMode() {
        run(true, "innbucks_validate", true);

        assertThat(events()).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage())
                    .contains("INNBUCKS-VALIDATE").contains("ELIGIBILITY");
        });
    }

    @Test
    @DisplayName("enabled + unconfigured → HALF-PROVISIONED naming BANK_API_*, NOT the assertion public-key var")
    void enabledButUnconfigured_namesTheRightVars() {
        run(true, "innbucks_validate", false);

        assertThat(events()).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("HALF-PROVISIONED").contains("BANK_API_");
            // The bug this guards: falling through to the default arm would name
            // the assertion-mode public key, sending the operator to the wrong var.
            assertThat(e.getFormattedMessage()).doesNotContain("PUBLIC_KEY");
        });
    }
}
