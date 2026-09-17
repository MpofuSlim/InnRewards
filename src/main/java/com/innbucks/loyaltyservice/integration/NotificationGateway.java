package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Delivers an issued voucher to the recipient's phone. Channel order is
 * WhatsApp-primary, SMS-fallback (the onboarded-customer convention; InnBucks
 * brand), {@link Async @Async} on the {@code notificationExecutor} so voucher
 * issuance never blocks on the gateway, and strictly best-effort: a delivery
 * failure is logged and swallowed and never affects the already-issued voucher.
 *
 * <p>The message carries the redeemable CODE — the customer needs it to redeem —
 * but we NEVER log the code (it's a bearer instrument). Logs show only the
 * voucher id and a masked phone, so a leaked log can't be used to redeem or to
 * harvest MSISDNs.
 */
@Slf4j
@Component
public class NotificationGateway {

    private static final DateTimeFormatter EXPIRY_FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

    private final SmsNotificationClient sms;
    private final WhatsAppNotificationClient whatsApp;

    public NotificationGateway(SmsNotificationClient sms, WhatsAppNotificationClient whatsApp) {
        this.sms = sms;
        this.whatsApp = whatsApp;
    }

    /**
     * Deliver the voucher to {@code recipientPhone}. Channel {@code NONE} (or a
     * missing phone) is a no-op. WhatsApp first, SMS fallback; never throws.
     */
    @Async("notificationExecutor")
    public void deliver(Voucher voucher, String recipientPhone) {
        Voucher.DeliveryChannel channel = voucher.getDeliveryChannel();
        if (channel == null || channel == Voucher.DeliveryChannel.NONE) {
            return;
        }
        if (recipientPhone == null || recipientPhone.isBlank()) {
            log.info("Voucher id={} has no deliverable phone — not sent (still issued)", voucher.getId());
            return;
        }
        String message = buildMessage(voucher);
        String ref = "VOUCHER-" + voucher.getId();
        try {
            whatsApp.sendCustomNotification(recipientPhone, message);
            log.info("Voucher id={} delivered via WhatsApp -> {}",
                    voucher.getId(), MsisdnMasking.mask(recipientPhone));
            return;
        } catch (RuntimeException e) {
            log.warn("Voucher id={} WhatsApp delivery failed for {}, falling back to SMS: {}",
                    voucher.getId(), MsisdnMasking.mask(recipientPhone), e.getMessage());
        }
        try {
            sms.sendSms(recipientPhone, message, ref);
            log.info("Voucher id={} delivered via SMS -> {}",
                    voucher.getId(), MsisdnMasking.mask(recipientPhone));
        } catch (RuntimeException e) {
            log.warn("Voucher id={} delivery failed on both channels for {} (still issued): {}",
                    voucher.getId(), MsisdnMasking.mask(recipientPhone), e.getMessage());
        }
    }

    /**
     * Expiry-warning nudge from the daily sweep: the voucher lapses soon and is
     * still unredeemed. Same channel order (WhatsApp first, SMS fallback) and
     * best-effort contract as {@link #deliver}. The code itself is NOT resent —
     * it was delivered at issuance; this is only the reminder.
     */
    @Async("notificationExecutor")
    public void warnExpiring(Voucher voucher, String recipientPhone, LocalDate expiresOn) {
        if (voucher == null || recipientPhone == null || recipientPhone.isBlank() || expiresOn == null) {
            return;
        }
        String worth = describeValue(voucher);
        String message = "Reminder: your InnBucks voucher"
                + (worth == null ? "" : " (" + worth + ")")
                + " expires on " + expiresOn
                + ". Redeem it before then so it does not go to waste.";
        String ref = "VOUCHER-EXPIRY-" + voucher.getId();
        try {
            whatsApp.sendCustomNotification(recipientPhone, message);
            log.info("Voucher id={} expiry warning sent via WhatsApp -> {}",
                    voucher.getId(), MsisdnMasking.mask(recipientPhone));
            return;
        } catch (RuntimeException e) {
            log.warn("Voucher id={} expiry-warning WhatsApp failed for {}, falling back to SMS: {}",
                    voucher.getId(), MsisdnMasking.mask(recipientPhone), e.getMessage());
        }
        try {
            sms.sendSms(recipientPhone, message, ref);
            log.info("Voucher id={} expiry warning sent via SMS -> {}",
                    voucher.getId(), MsisdnMasking.mask(recipientPhone));
        } catch (RuntimeException e) {
            log.warn("Voucher id={} expiry warning failed on both channels for {}: {}",
                    voucher.getId(), MsisdnMasking.mask(recipientPhone), e.getMessage());
        }
    }

    /**
     * The sender's own confirmation copy of an issued voucher (V46) — the
     * "both get the WhatsApp messages" half. Same channel order (WhatsApp
     * first, SMS fallback), same {@code NONE}/blank-phone no-op and same
     * best-effort contract as {@link #deliver}. Includes the code: at issue
     * time the sender is the party who minted it and already holds it in the
     * API response, so nothing new is disclosed. <b>Issue-path only</b> — the
     * transfer path rotates the code away from the sender by design, and a
     * copy there would hand the rotation right back.
     */
    @Async("notificationExecutor")
    public void deliverSenderCopy(Voucher voucher, String senderPhone) {
        Voucher.DeliveryChannel channel = voucher.getDeliveryChannel();
        if (channel == null || channel == Voucher.DeliveryChannel.NONE) {
            return;
        }
        if (senderPhone == null || senderPhone.isBlank()) {
            return;
        }
        String message = buildSenderCopyMessage(voucher);
        String ref = "VOUCHER-SENDER-" + voucher.getId();
        try {
            whatsApp.sendCustomNotification(senderPhone, message);
            log.info("Sender copy sent (WhatsApp) voucherId={} to {}",
                    voucher.getId(), MsisdnMasking.mask(senderPhone));
            return;
        } catch (RuntimeException e) {
            log.warn("Sender copy WhatsApp attempt failed voucherId={} to {}, retrying as SMS: {}",
                    voucher.getId(), MsisdnMasking.mask(senderPhone), e.getMessage());
        }
        try {
            sms.sendSms(senderPhone, message, ref);
            log.info("Sender copy sent (SMS) voucherId={} to {}",
                    voucher.getId(), MsisdnMasking.mask(senderPhone));
        } catch (RuntimeException e) {
            log.warn("Sender copy undeliverable on either channel voucherId={} to {} (voucher unaffected): {}",
                    voucher.getId(), MsisdnMasking.mask(senderPhone), e.getMessage());
        }
    }

    private String buildMessage(Voucher voucher) {
        String name = (voucher.getAssigneeName() != null && !voucher.getAssigneeName().isBlank())
                ? voucher.getAssigneeName() : "there";
        StringBuilder sb = new StringBuilder("Hi ").append(name).append(", ");
        // A named sender turns the platform's notification into a personal
        // gift: "Tawanda Mpofu sent you an InnBucks voucher" (V46).
        if (voucher.getSenderName() != null && !voucher.getSenderName().isBlank()) {
            sb.append(voucher.getSenderName()).append(" sent you an InnBucks voucher. Code ");
        } else {
            sb.append("your InnBucks voucher is ready. Code ");
        }
        sb.append(voucher.getCode());
        String worth = describeValue(voucher);
        if (worth != null) {
            sb.append(" (").append(worth).append(")");
        }
        sb.append(".");
        if (voucher.getExpiresAt() != null) {
            sb.append(" Valid until ").append(EXPIRY_FMT.format(voucher.getExpiresAt())).append(".");
        }
        sb.append(" Show this code at checkout to redeem.");
        return sb.toString();
    }

    /**
     * "Hi Tawanda Mpofu, your InnBucks voucher for Sedrick Nyanyiwa
     * (+263786546765) has been sent. Code ABC123 (USD 5 off). Valid until
     * 17 Sep 2027." The recipient's full number is fine in the MESSAGE — the
     * sender typed it — but never in logs, which stay masked.
     */
    private String buildSenderCopyMessage(Voucher voucher) {
        StringBuilder sb = new StringBuilder("Hi ");
        sb.append(voucher.getSenderName() != null && !voucher.getSenderName().isBlank()
                ? voucher.getSenderName() : "there");
        sb.append(", your InnBucks voucher");
        boolean hasName = voucher.getAssigneeName() != null && !voucher.getAssigneeName().isBlank();
        boolean hasPhone = voucher.getAssigneePhone() != null && !voucher.getAssigneePhone().isBlank();
        if (hasName || hasPhone) {
            sb.append(" for ");
            if (hasName) {
                sb.append(voucher.getAssigneeName());
                if (hasPhone) {
                    sb.append(" (").append(voucher.getAssigneePhone()).append(")");
                }
            } else {
                sb.append(voucher.getAssigneePhone());
            }
        }
        sb.append(" has been sent. Code ").append(voucher.getCode());
        String worth = describeValue(voucher);
        if (worth != null) {
            sb.append(" (").append(worth).append(")");
        }
        sb.append(".");
        if (voucher.getExpiresAt() != null) {
            sb.append(" Valid until ").append(EXPIRY_FMT.format(voucher.getExpiresAt())).append(".");
        }
        return sb.toString();
    }

    /**
     * Short human description of the voucher's worth, or null when the row
     * carries no value. Amount-only since V45 — a voucher's value is always
     * money in its currency; the legacy PERCENT/FREE_ITEM/COMBO rows this
     * used to render are historical and no longer delivered.
     */
    private String describeValue(Voucher voucher) {
        BigDecimal v = voucher.getValue();
        if (v == null) {
            return null;
        }
        String currency = (voucher.getCurrency() == null || voucher.getCurrency().isBlank())
                ? "" : voucher.getCurrency() + " ";
        return currency + v.stripTrailingZeros().toPlainString() + " off";
    }
}
