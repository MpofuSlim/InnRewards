package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.Voucher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stub façade for SMS, WhatsApp, email, push, and POS distribution channels.
 * Integrations replace these methods later; for now we log so QA can observe
 * that the right notifications would have fired.
 */
@Component
public class NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(NotificationGateway.class);

    public void deliver(Voucher voucher, Voucher.DeliveryChannel channel) {
        if (channel == null || channel == Voucher.DeliveryChannel.NONE) return;
        // Log the voucher ID (not the redeemable code — it's a bearer instrument)
        // and a masked recipient phone, so logs can't be used to redeem or to
        // harvest MSISDNs.
        String phone = com.innbucks.loyaltyservice.util.MsisdnMasking.mask(voucher.getAssigneePhone());
        switch (channel) {
            case SMS -> log.info("SMS voucher id={} -> {}", voucher.getId(), phone);
            case WHATSAPP -> log.info("WhatsApp voucher id={} -> {}", voucher.getId(), phone);
            case EMAIL -> log.info("Email voucher id={} -> assignee {}", voucher.getId(), voucher.getAssignedUserId());
            case PUSH -> log.info("Push voucher id={} -> assignee {}", voucher.getId(), voucher.getAssignedUserId());
            case POS -> log.info("POS voucher id={} forwarded to terminal", voucher.getId());
        }
    }
}
