package com.innbucks.loyaltyservice.integration;

/**
 * Thrown when an outbound notification gateway (SMS, WhatsApp) rejects a
 * message or is unreachable. Guest-checkout congratulations treat it as
 * best-effort — logged and swallowed by {@link GuestCheckoutNotifier} — so a
 * gateway hiccup never affects the already-committed checkout.
 */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
