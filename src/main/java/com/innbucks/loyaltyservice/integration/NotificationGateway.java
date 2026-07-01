package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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

    private String buildMessage(Voucher voucher) {
        String name = (voucher.getAssigneeName() != null && !voucher.getAssigneeName().isBlank())
                ? voucher.getAssigneeName() : "there";
        StringBuilder sb = new StringBuilder("Hi ").append(name)
                .append(", your InnBucks voucher is ready! Code: ").append(voucher.getCode());
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

    /** Short human description of the voucher's worth, or null if not applicable. */
    private String describeValue(Voucher voucher) {
        VoucherTemplate.ValueType type = voucher.getValueType();
        if (type == null) {
            return null;
        }
        BigDecimal v = voucher.getValue();
        return switch (type) {
            case AMOUNT -> v == null ? null
                    : ((voucher.getCurrency() == null || voucher.getCurrency().isBlank())
                            ? "" : voucher.getCurrency() + " ")
                        + v.stripTrailingZeros().toPlainString() + " off";
            case PERCENT -> v == null ? null : v.stripTrailingZeros().toPlainString() + "% off";
            case FREE_ITEM -> "free item";
            case COMBO -> "combo deal";
        };
    }
}
