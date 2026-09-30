package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.integration.WhatsAppNotificationClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The support messaging config refuses to BOOT in the two shapes that would
 * quietly break it: a WhatsApp cap the gateway can never meet, and an allowed
 * link host that is not a plain ASCII host name — which is how a look-alike
 * domain would get onto the allow-list.
 */
class SupportPropertiesTest {

    private static SupportProperties.Messages messages(Integer whatsappMax, List<String> hosts) {
        return new SupportProperties.Messages(null, null, whatsappMax, hosts, null, null);
    }

    @Test
    @DisplayName("defaults bind: 459 / 1000 / innbucks.co.zw / 60 / 5, and a 12h lookup TTL")
    void defaults() {
        SupportProperties p = SupportProperties.defaults();
        assertThat(p.lookupTtl()).hasHours(12);
        assertThat(p.messages().smsMaxCharacters()).isEqualTo(459);
        assertThat(p.messages().whatsappMaxCharacters()).isEqualTo(1000);
        assertThat(p.messages().allowedLinkHosts()).containsExactly("innbucks.co.zw");
        assertThat(p.messages().perAgentPerHour()).isEqualTo(60);
        assertThat(p.messages().perRecipientPerDay()).isEqualTo(5);
    }

    @Test
    @DisplayName("a WhatsApp cap above the gateway's own is refused at boot; exactly the gateway cap is fine")
    void whatsappCapAboveTheGateway_refusesToBoot() {
        int gateway = WhatsAppNotificationClient.MAX_MESSAGE_LENGTH;

        assertThat(messages(gateway, null).whatsappMaxCharacters()).isEqualTo(gateway);
        assertThatThrownBy(() -> messages(gateway + 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whatsapp-max-characters");
    }

    @Test
    @DisplayName("an allowed host that is not plain ASCII — a look-alike — is refused at boot")
    void nonAsciiAllowedHost_refusesToBoot() {
        assertThatThrownBy(() -> messages(null, List.of("innbucks.cо.zw")))   // Cyrillic o
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-link-hosts");
        assertThatThrownBy(() -> messages(null, List.of("xn--innbcks-9za.co.zw", "innücks.co.zw")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an allowed host carrying a path is refused at boot")
    void allowedHostWithAPath_refusesToBoot() {
        assertThatThrownBy(() -> messages(null, List.of("innbucks.co.zw/help")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-link-hosts");
    }

    @Test
    @DisplayName("plain ASCII hosts are accepted, lower-cased, with a leading dot dropped")
    void plainAsciiHosts_areAccepted() {
        assertThat(messages(null, List.of("InnBucks.co.zw", ".help.innbucks.co.zw", " ")).allowedLinkHosts())
                .containsExactly("innbucks.co.zw", "help.innbucks.co.zw");
    }
}
