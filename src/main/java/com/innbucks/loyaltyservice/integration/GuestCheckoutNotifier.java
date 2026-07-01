package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Sends a short congratulations message to a walk-in customer after a successful
 * guest shop-checkout: thanks for shopping, points earned, total points, and a
 * CTA to register on the InnBucks App to redeem them.
 *
 * <p>Delivery is SMS-primary, WhatsApp-fallback (try SMS; only if SMS fails,
 * try WhatsApp) and runs on the {@code notificationExecutor} pool via
 * {@link Async @Async} so it never delays the checkout's 201. It is strictly
 * best-effort: every failure is logged and swallowed — this method never
 * throws.
 */
@Slf4j
@Component
public class GuestCheckoutNotifier {

    private static final String DEFAULT_SHOP_NAME = "our shop";

    private final SmsNotificationClient sms;
    private final WhatsAppNotificationClient whatsApp;

    public GuestCheckoutNotifier(SmsNotificationClient sms, WhatsAppNotificationClient whatsApp) {
        this.sms = sms;
        this.whatsApp = whatsApp;
    }

    @Async("notificationExecutor")
    public void notifyPointsEarned(String shopName, String phoneNumber,
                                   BigDecimal pointsEarned, BigDecimal totalPoints) {
        if (phoneNumber == null || phoneNumber.isBlank()
                || pointsEarned == null || pointsEarned.signum() <= 0) {
            // Nothing earned (or no phone) → nothing to congratulate.
            log.debug("Skipping guest-checkout notification: phone present={} pointsEarned={}",
                    phoneNumber != null && !phoneNumber.isBlank(), pointsEarned);
            return;
        }

        String message = buildMessage(shopName, pointsEarned, totalPoints);

        // Primary channel: SMS (universal, reaches a guest with no InnBucks account).
        try {
            sms.sendSms(phoneNumber, message, null);
            return;
        } catch (RuntimeException e) {
            log.warn("Guest-checkout SMS failed for {}, falling back to WhatsApp: {}",
                    MsisdnMasking.mask(phoneNumber), e.getMessage());
        }

        // Fallback channel: WhatsApp.
        try {
            whatsApp.sendCustomNotification(phoneNumber, message);
        } catch (RuntimeException e) {
            log.warn("Guest-checkout notification failed on both channels for {}: {}",
                    MsisdnMasking.mask(phoneNumber), e.getMessage());
        }
    }

    private String buildMessage(String shopName, BigDecimal pointsEarned, BigDecimal totalPoints) {
        String shop = (shopName == null || shopName.isBlank()) ? DEFAULT_SHOP_NAME : shopName;
        return "Thanks for shopping at " + shop + "! You earned " + fmt(pointsEarned)
                + " loyalty points (total: " + fmt(totalPoints)
                + "). Register/Sign in on the InnBucks App to redeem them.";
    }

    private String fmt(BigDecimal b) {
        if (b == null) return "0";
        return b.stripTrailingZeros().toPlainString();
    }
}
