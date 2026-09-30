package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the support oversight feed: an agent looked at, or did, something
 * (V55). Append-only — {@link Immutable} stops Hibernate ever issuing an UPDATE,
 * and {@code SupportActivityRepository} exposes no delete.
 *
 * <p>A {@code CUSTOMER_LOOKUP} row doubles as the lookup SESSION: its id is the
 * {@code lookupId} every drill-down carries, and it resolves back to its phone
 * only for the agent who made it and only for {@code loyalty.support.lookup-ttl}.
 *
 * <p>{@code action} is a plain string, not an {@code @Enumerated} column, on
 * purpose: a row written by a newer build with an action this build does not
 * know must still hydrate, rather than 500 every read of the feed.
 */
@Entity
@Immutable
@Table(name = "support_activity")
@Getter
@Setter
@NoArgsConstructor
public class SupportActivity {

    public static final String SUBJECT_PHONE = "PHONE";
    public static final String SUBJECT_VOUCHER = "VOUCHER";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "agent_uuid", nullable = false, length = 64)
    private String agentUuid;

    @Column(name = "agent_login", length = 255)
    private String agentLogin;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "subject_kind", length = 20)
    private String subjectKind;

    @Column(name = "subject_id", length = 80)
    private String subjectId;

    /** Small JSON of ids / enums / amounts. Never free text, never a raw phone. */
    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * Every action this build writes. The column stays a string (see the class
     * doc); this is the vocabulary the writers use and the oversight filter binds.
     */
    public enum Action {
        /** A lookup that found nobody on record. Detail: the MASKED phone + found=false. */
        SEARCH,
        /** A lookup that found the customer; this row's id is the lookupId. */
        CUSTOMER_LOOKUP,
        VIEW_CUSTOMER,
        VIEW_TRANSACTIONS,
        VIEW_LEDGER,
        VIEW_VOUCHERS,
        VIEW_VOUCHER_ORDERS,
        VIEW_NOTES,
        VIEW_MESSAGES,
        NOTE_ADDED,
        /** A typed message or a voucher resend. Detail: messageId, kind, channel, outcome. */
        MESSAGE_SENT,
        SESSIONS_REVOKED,
        POINTS_ADJUSTED,
        TRANSACTION_REVERSED,
        MEMBERSHIP_UNBLOCKED
    }
}
