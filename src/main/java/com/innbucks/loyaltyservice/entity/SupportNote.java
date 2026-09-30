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
 * An internal support note about a customer (V55). Append-only: no edit and no
 * delete, anywhere — a support log that can be rewritten proves nothing.
 * {@link Immutable} and a repository with no delete make that structural.
 */
@Entity
@Immutable
@Table(name = "support_note")
@Getter
@Setter
@NoArgsConstructor
public class SupportNote {

    /** Column width; the API refuses a longer body with 400 before anything is written. */
    public static final int MAX_BODY = 2000;

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "subject_kind", nullable = false, length = 20)
    private String subjectKind;

    @Column(name = "subject_id", nullable = false, length = 80)
    private String subjectId;

    @Column(nullable = false, length = MAX_BODY)
    private String body;

    @Column(name = "agent_uuid", nullable = false, length = 64)
    private String agentUuid;

    @Column(name = "agent_login", length = 255)
    private String agentLogin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
