package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A support-initiated message to a customer and what became of it (V54).
 *
 * <p>Written {@link Outcome#PENDING} BEFORE the gateway is called — that
 * insert is what claims the rate-limit slot — then completed to SENT or FAILED
 * after, in a separate short transaction, so no database transaction is ever
 * held open across somebody else's network call.
 *
 * <p>{@code body} is exactly what was sent, and NULL for a secret-bearing kind
 * ({@link #KIND_VOUCHER_RESEND} carries a voucher code); the database refuses
 * the combination too ({@code chk_support_message_secret_body}).
 *
 * <p>{@code kind} and {@code recipientRole} are strings, not enums: the
 * contract shares both vocabularies with marketplace-service, and a row a newer
 * build wrote must still hydrate here.
 */
@Entity
@Table(name = "support_message")
@Getter
@Setter
@NoArgsConstructor
public class SupportMessage {

    public static final String KIND_CUSTOM = "CUSTOM";
    public static final String KIND_VOUCHER_RESEND = "VOUCHER_RESEND";

    public static final String ROLE_CUSTOMER = "CUSTOMER";
    public static final String ROLE_VOUCHER_HOLDER = "VOUCHER_HOLDER";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "subject_kind", length = 20)
    private String subjectKind;

    @Column(name = "subject_id", length = 80)
    private String subjectId;

    /** Full E.164 at rest; every response masks it. */
    @Column(name = "recipient_msisdn", nullable = false, length = 20)
    private String recipientMsisdn;

    @Column(name = "recipient_role", nullable = false, length = 30)
    private String recipientRole;

    @Column(nullable = false, length = 30)
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_requested", nullable = false, length = 20)
    private Channel channelRequested;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivered_via", length = 20)
    private DeliveredVia deliveredVia;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Outcome outcome = Outcome.PENDING;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "agent_uuid", nullable = false, length = 64)
    private String agentUuid;

    @Column(name = "agent_login", length = 255)
    private String agentLogin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    /** What the agent asked for. {@code SMS_THEN_WHATSAPP} tries WhatsApp only if SMS fails. */
    public enum Channel { SMS, WHATSAPP, SMS_THEN_WHATSAPP }

    /** What actually carried it. Null while PENDING and on FAILED. */
    public enum DeliveredVia { SMS, WHATSAPP }

    public enum Outcome { PENDING, SENT, FAILED }

    /** True for a kind whose text carries a secret and must never be stored or returned. */
    public static boolean secretBearing(String kind) {
        return KIND_VOUCHER_RESEND.equals(kind);
    }
}
