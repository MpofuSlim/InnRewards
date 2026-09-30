package com.innbucks.loyaltyservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * {@code loyalty.support.*} — the customer-support surface (V54).
 *
 * <p>Kept apart from {@link LoyaltyProperties}, whose record is built
 * positionally in many tests. Every field is BOXED with its default applied in
 * the constructor, for the same reason {@link VoucherGuardProperties} is: a
 * missing primitive binds as 0, and a limit of 0 would refuse every send.
 *
 * @param lookupTtl how long a lookup session resolves back to its phone for the
 *                  agent who made it. After that the agent looks the customer up
 *                  again, which is logged again.
 * @param messages  record-bound customer messaging
 */
@ConfigurationProperties("loyalty.support")
public record SupportProperties(Duration lookupTtl, Messages messages) {

    public SupportProperties {
        lookupTtl = lookupTtl == null ? Duration.ofHours(12) : lookupTtl;
        messages = messages == null ? Messages.defaults() : messages;
        if (lookupTtl.isNegative() || lookupTtl.isZero()) {
            throw new IllegalArgumentException("loyalty.support.lookup-ttl must be positive");
        }
    }

    public static SupportProperties defaults() {
        return new SupportProperties(null, null);
    }

    /**
     * @param signature             appended on its own line to every typed message
     * @param smsMaxCharacters      cap on the FINAL SMS text (after transliteration,
     *                              signature included); 459 = three GSM-7 segments
     * @param whatsappMaxCharacters cap on the final WhatsApp text; must stay at or
     *                              under the gateway's own 1600
     * @param allowedLinkHosts      hosts a link in a typed message may point at;
     *                              subdomains of each are allowed too
     * @param perAgentPerHour       attempts one agent may make in a rolling hour
     * @param perRecipientPerDay    attempts one number may receive in a rolling 24h
     */
    public record Messages(String signature,
                           Integer smsMaxCharacters,
                           Integer whatsappMaxCharacters,
                           List<String> allowedLinkHosts,
                           Integer perAgentPerHour,
                           Integer perRecipientPerDay) {

        /** The WhatsApp gateway refuses anything longer; a configured cap above it could never be met. */
        public static final int WHATSAPP_GATEWAY_CAP = 1600;

        public Messages {
            signature = signature == null ? "- InnBucks Loyalty Support" : signature.strip();
            smsMaxCharacters = smsMaxCharacters == null ? 459 : smsMaxCharacters;
            whatsappMaxCharacters = whatsappMaxCharacters == null ? 1000 : whatsappMaxCharacters;
            allowedLinkHosts = normaliseHosts(allowedLinkHosts == null ? List.of("innbucks.co.zw") : allowedLinkHosts);
            perAgentPerHour = perAgentPerHour == null ? 60 : perAgentPerHour;
            perRecipientPerDay = perRecipientPerDay == null ? 5 : perRecipientPerDay;
            if (smsMaxCharacters < 1) {
                throw new IllegalArgumentException("loyalty.support.messages.sms-max-characters must be positive");
            }
            if (whatsappMaxCharacters < 1 || whatsappMaxCharacters > WHATSAPP_GATEWAY_CAP) {
                throw new IllegalArgumentException("loyalty.support.messages.whatsapp-max-characters must be 1.."
                        + WHATSAPP_GATEWAY_CAP);
            }
            if (perAgentPerHour < 1 || perRecipientPerDay < 1) {
                throw new IllegalArgumentException("loyalty.support.messages limits must be at least 1");
            }
        }

        public static Messages defaults() {
            return new Messages(null, null, null, null, null, null);
        }

        private static List<String> normaliseHosts(List<String> hosts) {
            return hosts.stream()
                    .filter(h -> h != null && !h.isBlank())
                    .map(h -> h.strip().toLowerCase(Locale.ROOT))
                    .map(h -> h.startsWith(".") ? h.substring(1) : h)
                    .distinct()
                    .toList();
        }
    }
}
