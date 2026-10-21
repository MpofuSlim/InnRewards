package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import com.innbucks.loyaltyservice.util.VoucherCodes;
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
    private final MerchantRepository merchants;

    public NotificationGateway(SmsNotificationClient sms, WhatsAppNotificationClient whatsApp,
                               MerchantRepository merchants) {
        this.sms = sms;
        this.whatsApp = whatsApp;
        this.merchants = merchants;
    }

    /**
     * The name of the merchant the voucher is redeemable at, or null. Every
     * message used to say only "show this code at checkout", which tells the
     * holder nothing about WHERE — and a voucher is refused at any other
     * merchant (WRONG_MERCHANT). Redemption is merchant-wide, not shop-bound,
     * so "any Pizza Inn" is accurate. Best-effort: a failed lookup costs the
     * name, never the message.
     */
    private String merchantName(Voucher voucher) {
        return merchantName(voucher.getId(), voucher.getMerchantId());
    }

    private String merchantName(java.util.UUID voucherId, java.util.UUID merchantId) {
        if (merchantId == null || merchants == null) {
            return null;
        }
        try {
            return merchants.findById(merchantId)
                    .map(Merchant::getName)
                    .map(String::trim)
                    .filter(n -> !n.isEmpty())
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Voucher id={} merchant name lookup failed; message sent without it: {}",
                    voucherId, e.getMessage());
            return null;
        }
    }

    /**
     * Deliver the voucher to {@code recipientPhone}: <b>WhatsApp first, SMS
     * fallback</b>. Never throws — a voucher that could not be sent is still
     * issued.
     *
     * <p><b>The channel is not a routing choice and never was.</b> Every value
     * other than {@code NONE} takes exactly this path, so a voucher issued
     * "by EMAIL" or "by PUSH" was always sent a WhatsApp, and one issued "by
     * SMS" was sent a WhatsApp too and reached SMS only if WhatsApp threw. The
     * enum offered five routes the service cannot perform; the operator picking
     * one was choosing nothing. `NotificationGatewayTest` has always shown this
     * — its fallback case passes {@code SMS} and asserts WhatsApp was tried.
     *
     * <p><b>An ABSENT channel now DELIVERS.</b> It used to suppress, which made
     * "the field was not sent" mean "never contact the customer" — the opposite
     * of what any caller omitting an inert field intends, and a silent one: the
     * voucher issues, the response carries the code, and nothing reaches the
     * person holding it. Only an explicit {@code NONE} suppresses now, which is
     * the single value in the enum that ever described something real.
     */
    @Async("notificationExecutor")
    public void deliver(Voucher voucher, String recipientPhone) {
        if (voucher.getDeliveryChannel() == Voucher.DeliveryChannel.NONE) {
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
     *
     * <p><b>Synchronous, unlike {@link #deliver}.</b> The sweep runs it on its
     * own caller-runs {@code expiryWarningExecutor}: the warning is stamped
     * before it is sent, so it must never be handed to the drop-and-count
     * {@code notificationExecutor}, where a busy pool would lose it for good.
     */
    public void warnExpiring(Voucher voucher, String recipientPhone, LocalDate expiresOn) {
        if (voucher == null || recipientPhone == null || recipientPhone.isBlank() || expiresOn == null) {
            return;
        }
        String worth = describeValue(voucher);
        String merchant = merchantName(voucher);
        String message = "Reminder: your InnBucks voucher"
                + (merchant == null ? "" : " for " + merchant)
                + (worth == null ? "" : " (" + worth + ")")
                + " expires on " + expiresOn
                + ". Redeem it" + (merchant == null ? "" : " at any " + merchant)
                + " before then so it does not go to waste.";
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
     * first, SMS fallback), same explicit-{@code NONE}/blank-phone no-op and
     * same best-effort contract as {@link #deliver} — including that an ABSENT
     * channel delivers rather than suppressing. Includes the code: at issue
     * time the sender is the party who minted it and already holds it in the
     * API response, so nothing new is disclosed. <b>Issue-path only</b> — the
     * transfer path rotates the code away from the sender by design, and a
     * copy there would hand the rotation right back.
     */
    @Async("notificationExecutor")
    public void deliverSenderCopy(Voucher voucher, String senderPhone) {
        if (voucher.getDeliveryChannel() == Voucher.DeliveryChannel.NONE) {
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

    /**
     * What one successful voucher use moved, snapshotted by
     * {@code VoucherService} BEFORE it registers the after-commit send, so the
     * message never reads the entity after its transaction (V61).
     *
     * @param used      money taken off the bill by this use, in {@code currency}
     * @param remaining what the voucher has left; zero when it is fully used
     */
    public record VoucherUse(java.util.UUID voucherId, java.util.UUID merchantId, String holderPhone,
                             BigDecimal used, BigDecimal remaining, String currency,
                             java.time.Instant expiresAt) {

        boolean fullyUsed() {
            return remaining == null || remaining.signum() <= 0;
        }
    }

    /**
     * Tells a voucher's holder that it was just used, and what is left (V61,
     * owner decision 2026-10-21: after EVERY successful use). WhatsApp first,
     * SMS fallback, best-effort — a failed message never affects the redemption,
     * which has already committed (the caller registers this through
     * {@code AfterCommit}).
     *
     * <ul>
     *   <li>Partial: "InnBucks voucher used at Pizza Inn. USD 20.00 used, USD
     *       30.00 left, valid until 20 Oct 2027."</li>
     *   <li>Full: "Your InnBucks voucher for Pizza Inn is now fully used (USD
     *       20.00 this time)."</li>
     * </ul>
     *
     * <p><b>Never the code.</b> The voucher's code is a bearer credential and
     * the holder already has it; a usage notice that repeated it would put it
     * on the screen of anyone glancing at a phone at the till. The text is also
     * SMS-safe as written — no {@code : / $ ! ?}, which {@code SmsTextSanitizer}
     * would rewrite — so both channels carry the same words. A failed merchant
     * lookup drops the name, never the message.
     */
    @Async("notificationExecutor")
    public void notifyVoucherUsed(VoucherUse use) {
        if (use == null || use.holderPhone() == null || use.holderPhone().isBlank()) {
            return;
        }
        String message = usedMessage(use, merchantName(use.voucherId(), use.merchantId()));
        String ref = "VOUCHER-USE-" + use.voucherId();
        try {
            whatsApp.sendCustomNotification(use.holderPhone(), message);
            log.info("Voucher id={} use notice sent via WhatsApp -> {}",
                    use.voucherId(), MsisdnMasking.mask(use.holderPhone()));
            return;
        } catch (RuntimeException e) {
            log.warn("Voucher id={} use notice WhatsApp failed for {}, falling back to SMS: {}",
                    use.voucherId(), MsisdnMasking.mask(use.holderPhone()), e.getMessage());
        }
        try {
            sms.sendSms(use.holderPhone(), message, ref);
            log.info("Voucher id={} use notice sent via SMS -> {}",
                    use.voucherId(), MsisdnMasking.mask(use.holderPhone()));
        } catch (RuntimeException e) {
            log.warn("Voucher id={} use notice failed on both channels for {} (redemption unaffected): {}",
                    use.voucherId(), MsisdnMasking.mask(use.holderPhone()), e.getMessage());
        }
    }

    /** The holder's usage notice — see {@link #notifyVoucherUsed}. Package-visible for its tests. */
    static String usedMessage(VoucherUse use, String merchant) {
        String currency = (use.currency() == null || use.currency().isBlank()) ? "" : use.currency() + " ";
        if (use.fullyUsed()) {
            StringBuilder sb = new StringBuilder("Your InnBucks voucher")
                    .append(merchant == null ? "" : " for " + merchant)
                    .append(" is now fully used");
            if (use.used() != null) {
                sb.append(" (").append(currency).append(money(use.used())).append(" this time)");
            }
            return sb.append(".").toString();
        }
        StringBuilder sb = new StringBuilder("InnBucks voucher used")
                .append(merchant == null ? "" : " at " + merchant)
                .append(". ");
        if (use.used() != null) {
            sb.append(currency).append(money(use.used())).append(" used, ");
        }
        sb.append(currency).append(money(use.remaining())).append(" left");
        if (use.expiresAt() != null) {
            sb.append(", valid until ").append(EXPIRY_FMT.format(use.expiresAt()));
        }
        return sb.append(".").toString();
    }

    /** A money figure as people read it: at least two decimals, never trailing noise beyond them. */
    private static String money(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.setScale(Math.max(2, stripped.scale()), java.math.RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    /**
     * The exact message a voucher's holder receives at issue — code included —
     * for the customer-support resend, so a resent voucher reads the same as the
     * original and there is one template, not two. The caller sends it only to
     * the holder's own phone and never stores or logs it.
     */
    public String issueMessage(Voucher voucher) {
        return buildMessage(voucher);
    }

    private String buildMessage(Voucher voucher) {
        String name = (voucher.getAssigneeName() != null && !voucher.getAssigneeName().isBlank())
                ? voucher.getAssigneeName() : "there";
        String merchant = merchantName(voucher);
        String forMerchant = merchant == null ? "" : " for " + merchant;
        StringBuilder sb = new StringBuilder("Hi ").append(name).append(", ");
        // A named sender turns the platform's notification into a personal
        // gift: "Tawanda Mpofu sent you an InnBucks voucher for Pizza Inn" (V46).
        // Not when the sender IS the holder (a voucher issued to oneself shows
        // the same person as sender and recipient): "Tawanda sent you" would
        // read as a gift from themselves.
        boolean selfIssued = com.innbucks.loyaltyservice.service.VoucherService.isSelfIssued(
                voucher.getSenderPhone(), voucher.getAssigneePhone());
        if (!selfIssued && voucher.getSenderName() != null && !voucher.getSenderName().isBlank()) {
            sb.append(voucher.getSenderName()).append(" sent you an InnBucks voucher")
                    .append(forMerchant).append(". Code ");
        } else {
            sb.append("your InnBucks voucher").append(forMerchant).append(" is ready. Code ");
        }
        // Grouped in fours for the person reading it — "9087-8765-9876-4566". The
        // till accepts it typed back with the hyphens, or with spaces, or raw
        // (VoucherCodes.normalize strips both).
        sb.append(VoucherCodes.display(voucher.getCode()));
        String worth = describeValue(voucher);
        if (worth != null) {
            sb.append(" (").append(worth).append(")");
        }
        sb.append(".");
        if (voucher.getExpiresAt() != null) {
            sb.append(" Valid until ").append(EXPIRY_FMT.format(voucher.getExpiresAt())).append(".");
        }
        sb.append(merchant == null
                ? " Show this code at checkout to redeem."
                : " Show this code at any " + merchant + " checkout to redeem.");
        return sb.toString();
    }

    /**
     * "Hi Tawanda Mpofu, your InnBucks voucher for Sedrick Nyanyiwa
     * (+263786546765) has been sent. Code 9087 8765 9876 4566 (USD 5 off). Valid until
     * 17 Sep 2027. It can be redeemed at any Pizza Inn." The recipient's full number is fine in the MESSAGE — the
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
        sb.append(" has been sent. Code ").append(VoucherCodes.display(voucher.getCode()));
        String worth = describeValue(voucher);
        if (worth != null) {
            sb.append(" (").append(worth).append(")");
        }
        sb.append(".");
        if (voucher.getExpiresAt() != null) {
            sb.append(" Valid until ").append(EXPIRY_FMT.format(voucher.getExpiresAt())).append(".");
        }
        String merchant = merchantName(voucher);
        if (merchant != null) {
            sb.append(" It can be redeemed at any ").append(merchant).append(".");
        }
        return sb.toString();
    }

    /**
     * Short human description of the voucher's worth, or null when the row
     * carries no value. Amount-only since V45 — a voucher's value is always
     * money in its currency; the legacy PERCENT/FREE_ITEM/COMBO rows this
     * used to render are historical and no longer delivered.
     *
     * <p><b>A part-used voucher quotes what is LEFT</b> (V61): "USD 30 left of
     * USD 50". This one description feeds the issue message, the support
     * resend and the expiry warning, and a resend or a warning that claimed
     * the full face value of a voucher already half spent would promise the
     * holder money that is gone. A voucher untouched since issue reads as it
     * always did ("USD 5 off").
     */
    private String describeValue(Voucher voucher) {
        BigDecimal v = voucher.getValue();
        if (v == null) {
            return null;
        }
        String currency = (voucher.getCurrency() == null || voucher.getCurrency().isBlank())
                ? "" : voucher.getCurrency() + " ";
        BigDecimal left = voucher.getRemainingBalance();
        if (left != null && left.compareTo(v) < 0) {
            return currency + left.stripTrailingZeros().toPlainString() + " left of "
                    + currency + v.stripTrailingZeros().toPlainString();
        }
        return currency + v.stripTrailingZeros().toPlainString() + " off";
    }
}
