package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.SupportProperties;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.dto.SupportDtos;
import com.innbucks.loyaltyservice.entity.SupportActivity;
import com.innbucks.loyaltyservice.entity.SupportMessage;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyDataException;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.integration.SmsNotificationClient;
import com.innbucks.loyaltyservice.integration.WhatsAppNotificationClient;
import com.innbucks.loyaltyservice.repository.SupportMessageRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.SupportAgent;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Record-bound customer messaging (owner decision, 2026-09-30): an agent may
 * type an SMS/WhatsApp ONLY to a customer on record, and never types the
 * destination. The recipient is the phone of the lookup the agent is holding —
 * resolved server-side from that lookup — so there is no field in any request
 * a number could be put in.
 *
 * <p>The order of a send is the contract's, and each step is load-bearing:
 * <ol>
 *   <li>Validate everything — channel provisioned (503 {@code channel_unavailable}
 *       before anything is written; SMS_THEN_WHATSAPP needs either channel),
 *       body, links, length.</li>
 *   <li>CLAIM: a PENDING {@code support_message} row, after both rate limits,
 *       in its own short transaction. That row is what makes the attempt
 *       count.</li>
 *   <li>Call the gateway(s), outside any transaction.</li>
 *   <li>COMPLETE: the outcome and the {@code MESSAGE_SENT} activity row.</li>
 * </ol>
 * All channels failing is a 502 {@code message_not_delivered} whose data is the
 * FAILED record — the attempt happened and is on file.
 */
@Service
@Slf4j
public class SupportMessageService {

    private final SupportCustomerService customers;
    private final SupportMessageLedger ledger;
    private final SupportMessageRepository messages;
    private final VoucherRepository vouchers;
    private final NotificationGateway notificationGateway;
    private final SmsNotificationClient sms;
    private final WhatsAppNotificationClient whatsApp;
    private final SupportProperties props;
    private final LoyaltyMetrics metrics;

    public SupportMessageService(SupportCustomerService customers, SupportMessageLedger ledger,
                                 SupportMessageRepository messages, VoucherRepository vouchers,
                                 NotificationGateway notificationGateway, SmsNotificationClient sms,
                                 WhatsAppNotificationClient whatsApp, SupportProperties props,
                                 LoyaltyMetrics metrics) {
        this.customers = customers;
        this.ledger = ledger;
        this.messages = messages;
        this.vouchers = vouchers;
        this.notificationGateway = notificationGateway;
        this.sms = sms;
        this.whatsApp = whatsApp;
        this.props = props;
        this.metrics = metrics;
    }

    // ---- Typed messages ----

    /** What a send would say and cost. Writes nothing, consumes no limit, and never refuses on length. */
    public SupportDtos.PreviewResponse preview(SupportAgent agent, UUID lookupId, SupportMessage.Channel channel,
                                               String rawBody) {
        SupportCustomerService.Customer customer = customers.resolve(agent, lookupId);
        SupportMessageComposer.Rendered rendered = composeTyped(rawBody);
        return previewOf(channel, SupportMessage.ROLE_CUSTOMER, customer.phone(), rendered);
    }

    public SupportDtos.MessageResponse send(SupportAgent agent, UUID lookupId, SupportMessage.Channel channel,
                                            String rawBody) {
        SupportCustomerService.Customer customer = customers.resolve(agent, lookupId);
        requireChannelAvailable(channel);
        SupportMessageComposer.Rendered rendered = composeTyped(rawBody);
        requireWithinLength(channel, rendered);
        SupportMessage claimed = ledger.claim(agent, new SupportMessageLedger.Draft(customer.phone(),
                SupportMessage.ROLE_CUSTOMER, SupportMessage.KIND_CUSTOM, channel,
                legsFor(channel).firstText(rendered), SupportActivity.SUBJECT_PHONE, customer.phone()));
        return deliverAndComplete(agent, claimed, customer.phone(), rendered,
                SupportActivityService.detail("lookupId", lookupId));
    }

    // ---- The voucher resend ----

    /**
     * Sends a voucher's ISSUE message — the same template, code included — again,
     * to its holder's own phone only. The voucher must be held by the looked-up
     * customer (the redemption holder rule) and still live. The code is never
     * stored ({@code body} NULL) and never returned.
     */
    public SupportDtos.MessageResponse resendVoucher(SupportAgent agent, UUID lookupId, UUID voucherId,
                                                     SupportMessage.Channel channel) {
        SupportCustomerService.Customer customer = customers.resolve(agent, lookupId);
        requireChannelAvailable(channel);
        Voucher voucher = vouchers.findById(voucherId)
                .filter(v -> SupportCustomerService.holds(customer, v))
                .orElseThrow(() -> new LoyaltyException(HttpStatus.NOT_FOUND, "voucher_not_found",
                        "This customer holds no voucher with that id."));
        boolean pastExpiry = voucher.getExpiresAt() != null && !voucher.getExpiresAt().isAfter(Instant.now());
        if (!Voucher.LIVE_STATUSES.contains(voucher.getStatus()) || pastExpiry) {
            throw LoyaltyException.conflict("voucher_not_live",
                    "This voucher can no longer be redeemed (" + (pastExpiry ? "EXPIRED" : voucher.getStatus())
                            + "), so there is nothing to resend.");
        }
        SupportMessageComposer.Rendered rendered = SupportMessageComposer.render(
                notificationGateway.issueMessage(voucher));
        requireWithinLength(channel, rendered);
        SupportMessage claimed = ledger.claim(agent, new SupportMessageLedger.Draft(customer.phone(),
                SupportMessage.ROLE_VOUCHER_HOLDER, SupportMessage.KIND_VOUCHER_RESEND, channel, null,
                SupportActivity.SUBJECT_VOUCHER, voucher.getId().toString()));
        return deliverAndComplete(agent, claimed, customer.phone(), rendered,
                SupportActivityService.detail("lookupId", lookupId, "voucherId", voucher.getId()));
    }

    // ---- History ----

    @Transactional
    public PageResponse<SupportDtos.MessageResponse> forCustomer(SupportAgent agent, UUID lookupId,
                                                                 int page, int size) {
        SupportCustomerService.Customer customer = customers.resolve(agent, lookupId);
        Pageable pageable = PageRequest.of(SupportPaging.page(page), SupportPaging.size(size));
        customers.logView(agent, customer, SupportActivity.Action.VIEW_MESSAGES,
                "page", pageable.getPageNumber(), "size", pageable.getPageSize());
        return PageResponse.from(messages.findByRecipientMsisdnOrderByCreatedAtDesc(customer.phone(), pageable)
                .map(SupportMessageService::toResponse));
    }

    /** Every agent's messages, newest first — the supervisor's view. */
    @Transactional(readOnly = true)
    public PageResponse<SupportDtos.MessageResponse> oversight(String agentUuid, Instant from, Instant to,
                                                               int page, int size) {
        SupportPaging.requireOrderedRange(from, to);
        Specification<SupportMessage> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (agentUuid != null && !agentUuid.isBlank()) {
                where.add(cb.equal(root.get("agentUuid"), agentUuid.strip()));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("createdAt"), to));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Pageable pageable = PageRequest.of(SupportPaging.page(page), SupportPaging.size(size),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.from(messages.findAll(spec, pageable).map(SupportMessageService::toResponse));
    }

    // ---- The send itself ----

    private SupportDtos.MessageResponse deliverAndComplete(SupportAgent agent, SupportMessage claimed,
                                                           String recipient,
                                                           SupportMessageComposer.Rendered rendered,
                                                           Map<String, Object> extraDetail) {
        Delivery delivery = deliver(claimed.getChannelRequested(), recipient, rendered,
                "SUPPORT-" + claimed.getId());
        SupportMessage done = ledger.complete(agent, claimed, delivery.outcome(), delivery.via(),
                delivery.sentText(), delivery.failureCode(), recipient, extraDetail);
        metrics.incSupportMessage(done.getKind(), done.getOutcome().name());
        SupportDtos.MessageResponse response = toResponse(done);
        if (done.getOutcome() == SupportMessage.Outcome.FAILED) {
            throw new LoyaltyDataException(HttpStatus.BAD_GATEWAY, "message_not_delivered",
                    "The message could not be delivered on any channel. The attempt is recorded.", response);
        }
        return response;
    }

    private record Delivery(SupportMessage.Outcome outcome, SupportMessage.DeliveredVia via,
                            String sentText, String failureCode) {}

    /** Runs with NO transaction open: the rows are claimed before and completed after. */
    private Delivery deliver(SupportMessage.Channel channel, String recipient,
                             SupportMessageComposer.Rendered rendered, String reference) {
        Legs legs = legsFor(channel);
        if (legs.sms() && trySms(recipient, rendered, reference)) {
            return sent(SupportMessage.DeliveredVia.SMS, rendered.smsText());
        }
        if (legs.whatsApp() && tryWhatsApp(recipient, rendered)) {
            return sent(SupportMessage.DeliveredVia.WHATSAPP, rendered.whatsappText());
        }
        String failureCode = legs.sms() && legs.whatsApp() ? "sms_and_whatsapp_failed"
                : legs.sms() ? "sms_failed" : "whatsapp_failed";
        return failed(legs.firstText(rendered), failureCode);
    }

    private boolean trySms(String recipient, SupportMessageComposer.Rendered rendered, String reference) {
        try {
            sms.sendSms(recipient, rendered.smsText(), reference);
            return true;
        } catch (RuntimeException e) {
            // Never the body (it may carry a voucher code), never the full number.
            log.warn("Support SMS to {} failed ref={}: {}", MsisdnMasking.mask(recipient), reference, e.toString());
            return false;
        }
    }

    private boolean tryWhatsApp(String recipient, SupportMessageComposer.Rendered rendered) {
        try {
            whatsApp.sendCustomNotification(recipient, rendered.whatsappText());
            return true;
        } catch (RuntimeException e) {
            log.warn("Support WhatsApp to {} failed: {}", MsisdnMasking.mask(recipient), e.toString());
            return false;
        }
    }

    private static Delivery sent(SupportMessage.DeliveredVia via, String text) {
        return new Delivery(SupportMessage.Outcome.SENT, via, text, null);
    }

    private static Delivery failed(String attemptedText, String failureCode) {
        return new Delivery(SupportMessage.Outcome.FAILED, null, attemptedText, failureCode);
    }

    // ---- Validation ----

    /**
     * 503 before anything is written. SMS and WHATSAPP need their own channel;
     * SMS_THEN_WHATSAPP needs EITHER — it uses whichever legs this cell has, in
     * order, and is unavailable only when neither is provisioned.
     */
    private void requireChannelAvailable(SupportMessage.Channel channel) {
        switch (channel) {
            case SMS -> {
                if (!sms.isConfigured()) throw unavailable("SMS is not configured on this cell.");
            }
            case WHATSAPP -> {
                if (!whatsApp.isConfigured()) throw unavailable("WHATSAPP is not configured on this cell.");
            }
            case SMS_THEN_WHATSAPP -> {
                if (!sms.isConfigured() && !whatsApp.isConfigured()) {
                    throw unavailable("Neither SMS nor WHATSAPP is configured on this cell.");
                }
            }
        }
    }

    private static LoyaltyException unavailable(String what) {
        return LoyaltyException.serviceUnavailable("channel_unavailable", what + " Choose another channel.");
    }

    /**
     * The legs a send will actually attempt, in order. SMS_THEN_WHATSAPP tries
     * SMS when SMS is provisioned, and WhatsApp when WhatsApp is — after a
     * failed SMS, or directly when SMS is not provisioned.
     */
    private record Legs(boolean sms, boolean whatsApp) {

        /** The text that goes out first — what is stored at claim and shown by the preview. */
        String firstText(SupportMessageComposer.Rendered rendered) {
            return sms ? rendered.smsText() : rendered.whatsappText();
        }
    }

    private Legs legsFor(SupportMessage.Channel channel) {
        return switch (channel) {
            case SMS -> new Legs(true, false);
            case WHATSAPP -> new Legs(false, true);
            case SMS_THEN_WHATSAPP -> new Legs(sms.isConfigured(), whatsApp.isConfigured());
        };
    }

    /** Strip HTML, trim, refuse empty, check links, append the signature. */
    private SupportMessageComposer.Rendered composeTyped(String rawBody) {
        String body = rawBody == null ? "" : HtmlSanitizer.stripAll(rawBody).strip();
        if (body.isEmpty()) {
            throw LoyaltyException.badRequest("invalid_message_body",
                    "Type a message. It is empty once formatting is removed.");
        }
        SupportMessageComposer.firstDisallowedHost(body, props.messages().allowedLinkHosts()).ifPresent(host -> {
            throw new LoyaltyDataException(HttpStatus.BAD_REQUEST, "link_not_allowed",
                    "Links in a support message may only point at "
                            + String.join(", ", props.messages().allowedLinkHosts()) + " - '" + host
                            + "' is not one of them. If it is not meant as a link, add a space after the full stop.",
                    Map.of("host", host));
        });
        return SupportMessageComposer.withSignature(body, props.messages().signature());
    }

    private void requireWithinLength(SupportMessage.Channel channel, SupportMessageComposer.Rendered rendered) {
        int smsMax = props.messages().smsMaxCharacters();
        int waMax = props.messages().whatsappMaxCharacters();
        if (channel != SupportMessage.Channel.WHATSAPP && rendered.smsText().length() > smsMax) {
            throw tooLong("SMS", rendered.smsText().length(), smsMax);
        }
        if (channel != SupportMessage.Channel.SMS && rendered.whatsappText().length() > waMax) {
            throw tooLong("WHATSAPP", rendered.whatsappText().length(), waMax);
        }
    }

    private static LoyaltyDataException tooLong(String channel, int characters, int max) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("channel", channel);
        data.put("characters", characters);
        data.put("maxCharacters", max);
        return new LoyaltyDataException(HttpStatus.BAD_REQUEST, "message_too_long",
                "The " + channel + " text is " + characters + " characters; the limit is " + max + ".", data);
    }

    private SupportDtos.PreviewResponse previewOf(SupportMessage.Channel channel, String role, String phone,
                                                  SupportMessageComposer.Rendered rendered) {
        // The FIRST leg this cell will actually use: SMS_THEN_WHATSAPP on a cell
        // with no SMS goes straight to WhatsApp, so that is what is previewed.
        boolean whatsappOnly = !legsFor(channel).sms();
        String text = whatsappOnly ? rendered.whatsappText() : rendered.smsText();
        int max = whatsappOnly ? props.messages().whatsappMaxCharacters() : props.messages().smsMaxCharacters();
        return new SupportDtos.PreviewResponse(channel.name(), role, MsisdnMasking.mask(phone), text,
                text.length(), max,
                whatsappOnly ? null : SupportMessageComposer.smsSegments(rendered.smsText()),
                !whatsappOnly && rendered.transliterated());
    }

    static SupportDtos.MessageResponse toResponse(SupportMessage m) {
        return new SupportDtos.MessageResponse(m.getId(), m.getKind(),
                m.getChannelRequested() == null ? null : m.getChannelRequested().name(),
                m.getDeliveredVia() == null ? null : m.getDeliveredVia().name(),
                m.getOutcome() == null ? null : m.getOutcome().name(),
                m.getRecipientRole(),
                MsisdnMasking.mask(m.getRecipientMsisdn()),
                SupportMessage.secretBearing(m.getKind()) ? null : m.getBody(),
                new SupportDtos.AgentRef(m.getAgentUuid(), m.getAgentLogin()),
                m.getCreatedAt(), m.getCompletedAt(), m.getFailureCode());
    }
}
