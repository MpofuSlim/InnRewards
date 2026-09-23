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

import java.time.Instant;
import java.util.UUID;

/**
 * One move of {@link Merchant#getAdminEmail() a merchant's admin binding}
 * (V50). Append-only: written in the same transaction as the change it
 * records, never updated, never deleted.
 *
 * <p>Deliberately not an {@link Auditable}: that base class records the LAST
 * editor of a row, which is the opposite of what a history needs.
 */
@Entity
@Table(name = "merchant_admin_changes")
@Getter
@NoArgsConstructor
public class MerchantAdminChange {

    public enum ChangeType {
        /** The binding a merchant was created with — self-onboarding or on someone's behalf. */
        CREATED,
        /** A SUPER_ADMIN moved the merchant to a different admin. */
        REASSIGNED,
        /** A SUPER_ADMIN cleared the binding: nobody's sign-in resolves to the merchant. */
        UNBOUND
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, updatable = false, length = 16)
    private ChangeType changeType;

    @Column(name = "previous_email", updatable = false, length = 255)
    private String previousEmail;

    @Column(name = "new_email", updatable = false, length = 255)
    private String newEmail;

    /** Same identity the service's created_by/updated_by columns carry: the
     *  caller's user_uuid, else the JWT subject. */
    @Column(name = "changed_by", updatable = false, length = 255)
    private String changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    public MerchantAdminChange(UUID tenantId, UUID merchantId, ChangeType changeType,
                               String previousEmail, String newEmail, String changedBy, Instant changedAt) {
        this.tenantId = tenantId;
        this.merchantId = merchantId;
        this.changeType = changeType;
        this.previousEmail = previousEmail;
        this.newEmail = newEmail;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }
}
