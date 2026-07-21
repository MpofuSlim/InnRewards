package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Best-effort activity alerts to an ONBOARDED loyalty customer for the money
 * events on their wallet: points earned, redeemed, transferred (in / out),
 * adjusted, and the one-time "your points are now active" unlock when a
 * previously phone-keyed (PENDING) account finishes registration.
 *
 * <p>Channel order is SMS-primary, WhatsApp-fallback (product decision:
 * SMS reaches every handset, WhatsApp catches SMS delivery failures — same
 * order as {@link GuestCheckoutNotifier}). Brand is InnBucks.
 * Every method is {@link Async @Async} on the {@code notificationExecutor} so it
 * never delays the caller, and is strictly best-effort: all failures are logged
 * and swallowed — a notification never affects the (already-applied)
 * ledger/wallet change.
 */
@Slf4j
@Component
public class MemberActivityNotifier {

    private final SmsNotificationClient sms;
    private final WhatsAppNotificationClient whatsApp;

    public MemberActivityNotifier(SmsNotificationClient sms, WhatsAppNotificationClient whatsApp) {
        this.sms = sms;
        this.whatsApp = whatsApp;
    }

    @Async("notificationExecutor")
    public void notifyPointsEarned(String phone, BigDecimal earned, BigDecimal balance) {
        if (isBlank(phone) || isNonPositive(earned)) return;
        dispatch(phone, "You earned " + fmt(earned) + " InnBucks loyalty points. Balance: " + fmt(balance) + ".");
    }

    @Async("notificationExecutor")
    public void notifyPointsRedeemed(String phone, BigDecimal redeemed, BigDecimal balance) {
        if (isBlank(phone) || isNonPositive(redeemed)) return;
        dispatch(phone, "You redeemed " + fmt(redeemed) + " InnBucks loyalty points. Balance: " + fmt(balance) + ".");
    }

    @Async("notificationExecutor")
    public void notifyTransferSent(String phone, BigDecimal amount, BigDecimal balance) {
        if (isBlank(phone) || isNonPositive(amount)) return;
        dispatch(phone, "You sent " + fmt(amount) + " InnBucks loyalty points. Balance: " + fmt(balance) + ".");
    }

    @Async("notificationExecutor")
    public void notifyTransferReceived(String phone, BigDecimal amount, BigDecimal balance) {
        if (isBlank(phone) || isNonPositive(amount)) return;
        dispatch(phone, "You received " + fmt(amount) + " InnBucks loyalty points. Balance: " + fmt(balance) + ".");
    }

    @Async("notificationExecutor")
    public void notifyPointsAdjusted(String phone, BigDecimal delta, BigDecimal balance) {
        if (isBlank(phone) || delta == null || delta.signum() == 0) return;
        String body = delta.signum() > 0
                ? "Your InnBucks loyalty balance was credited " + fmt(delta) + " points."
                : "Your InnBucks loyalty balance was reduced by " + fmt(delta.abs()) + " points.";
        dispatch(phone, body + " Balance: " + fmt(balance) + ".");
    }

    /**
     * Retention nudge from the daily expiry-warning sweep: {@code amount}
     * points across the member's soon-to-expire lots lapse on
     * {@code expiresOn} unless spent.
     */
    @Async("notificationExecutor")
    public void notifyPointsExpiring(String phone, BigDecimal amount, LocalDate expiresOn) {
        if (isBlank(phone) || isNonPositive(amount) || expiresOn == null) return;
        dispatch(phone, fmt(amount) + " of your InnBucks loyalty points expire on " + expiresOn
                + ". Redeem them before then so they don't go to waste!");
    }

    @Async("notificationExecutor")
    public void notifyPointsUnlocked(String phone) {
        if (isBlank(phone)) return;
        dispatch(phone, "Good news! Your InnBucks loyalty points are now active and ready to spend.");
    }

    /** SMS-primary, WhatsApp-fallback; best-effort — never throws. */
    private void dispatch(String phone, String message) {
        try {
            sms.sendSms(phone, message, null);
            return;
        } catch (RuntimeException e) {
            log.warn("Member-activity SMS failed for {}, falling back to WhatsApp: {}",
                    MsisdnMasking.mask(phone), e.getMessage());
        }
        try {
            whatsApp.sendCustomNotification(phone, message);
        } catch (RuntimeException e) {
            log.warn("Member-activity notification failed on both channels for {}: {}",
                    MsisdnMasking.mask(phone), e.getMessage());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isNonPositive(BigDecimal b) {
        return b == null || b.signum() <= 0;
    }

    private static String fmt(BigDecimal b) {
        if (b == null) return "0";
        return b.stripTrailingZeros().toPlainString();
    }
}
