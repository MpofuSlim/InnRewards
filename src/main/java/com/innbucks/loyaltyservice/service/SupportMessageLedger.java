package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.SupportProperties;
import com.innbucks.loyaltyservice.dto.SupportDtos;
import com.innbucks.loyaltyservice.entity.SupportActivity;
import com.innbucks.loyaltyservice.entity.SupportMessage;
import com.innbucks.loyaltyservice.exception.LoyaltyDataException;
import com.innbucks.loyaltyservice.repository.SupportMessageRepository;
import com.innbucks.loyaltyservice.security.SupportAgent;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * The two short transactions around a support send: CLAIM (rate limits + the
 * PENDING row) and COMPLETE (the outcome + the activity row). The gateway call
 * happens between them, outside any transaction, so a slow SMS provider never
 * holds a database connection or a lock.
 *
 * <p><b>The claim is atomic per agent and per recipient.</b> The limits are
 * counted from {@code support_message} rows, and a count-then-insert is a race:
 * two sends a millisecond apart would both see 4 of 5 and both insert. Each
 * claim therefore takes two transaction-scoped Postgres advisory locks — the
 * agent's, then the recipient's, always in that order so two claims can never
 * wait on each other in a cycle — before it counts. Two claims for the same
 * number serialise for the few milliseconds it takes to count and insert; claims
 * for different numbers by different agents do not touch each other.
 */
@Component
public class SupportMessageLedger {

    static final Duration AGENT_WINDOW = Duration.ofHours(1);
    static final Duration RECIPIENT_WINDOW = Duration.ofHours(24);

    private final SupportMessageRepository messages;
    private final SupportActivityService activity;
    private final SupportProperties props;
    private final JdbcTemplate jdbc;

    public SupportMessageLedger(SupportMessageRepository messages, SupportActivityService activity,
                                SupportProperties props, DataSource dataSource) {
        this.messages = messages;
        this.activity = activity;
        this.props = props;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** What a claim is for. {@code body} is null for a secret-bearing kind. */
    public record Draft(String recipientMsisdn, String recipientRole, String kind,
                        SupportMessage.Channel channel, String body, String subjectKind, String subjectId) {}

    /**
     * Enforces both limits and writes the PENDING row, which is what claims the
     * slot: an attempt counts from this moment, whatever happens next.
     *
     * @throws LoyaltyDataException 429 {@code support_message_rate_limited}, with
     *         {@code data {scope, limit, windowMinutes}}; nothing is written
     */
    @Transactional
    public SupportMessage claim(SupportAgent agent, Draft draft) {
        lock("support-message:agent:" + agent.uuid());
        lock("support-message:recipient:" + draft.recipientMsisdn());
        Instant now = Instant.now();

        int perAgent = props.messages().perAgentPerHour();
        if (messages.countByAgentUuidAndCreatedAtAfter(agent.uuid(), now.minus(AGENT_WINDOW)) >= perAgent) {
            throw rateLimited("AGENT", perAgent, AGENT_WINDOW,
                    "You have sent " + perAgent + " customer messages in the last hour. Try again later.");
        }
        int perRecipient = props.messages().perRecipientPerDay();
        if (messages.countByRecipientMsisdnAndCreatedAtAfter(draft.recipientMsisdn(),
                now.minus(RECIPIENT_WINDOW)) >= perRecipient) {
            throw rateLimited("RECIPIENT", perRecipient, RECIPIENT_WINDOW,
                    "This customer has already been sent " + perRecipient
                            + " support messages in the last 24 hours. Try again later.");
        }

        SupportMessage row = new SupportMessage();
        row.setSubjectKind(draft.subjectKind());
        row.setSubjectId(draft.subjectId());
        row.setRecipientMsisdn(draft.recipientMsisdn());
        row.setRecipientRole(draft.recipientRole());
        row.setKind(draft.kind());
        row.setChannelRequested(draft.channel());
        row.setOutcome(SupportMessage.Outcome.PENDING);
        row.setBody(SupportMessage.secretBearing(draft.kind()) ? null : draft.body());
        row.setAgentUuid(agent.uuid());
        row.setAgentLogin(agent.login());
        row.setCreatedAt(now);
        return messages.saveAndFlush(row);
    }

    /**
     * Records what happened and writes the MESSAGE_SENT activity row in the same
     * transaction. Completing an already-completed row is a no-op.
     *
     * @param extraDetail ids to add to the activity detail (lookupId, voucherId)
     */
    @Transactional
    public SupportMessage complete(SupportAgent agent, SupportMessage claimed, SupportMessage.Outcome outcome,
                                   SupportMessage.DeliveredVia deliveredVia, String sentBody, String failureCode,
                                   String activityPhone, Map<String, Object> extraDetail) {
        String body = SupportMessage.secretBearing(claimed.getKind()) ? null : sentBody;
        messages.complete(claimed.getId(), outcome, deliveredVia, body, failureCode, Instant.now());
        Map<String, Object> detail = SupportActivityService.detail(
                "messageId", claimed.getId(),
                "kind", claimed.getKind(),
                "channel", claimed.getChannelRequested(),
                "outcome", outcome);
        detail.putAll(extraDetail);
        activity.record(agent, SupportActivity.Action.MESSAGE_SENT, SupportActivity.SUBJECT_PHONE,
                activityPhone, detail);
        return messages.findById(claimed.getId()).orElseThrow();
    }

    private void lock(String key) {
        // hashtext() folds the key to an int4; a collision only over-serialises.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, key);
    }

    private static LoyaltyDataException rateLimited(String scope, int limit, Duration window, String message) {
        return new LoyaltyDataException(HttpStatus.TOO_MANY_REQUESTS, "support_message_rate_limited", message,
                new SupportDtos.RateLimitDetail(scope, limit, (int) window.toMinutes()));
    }
}
