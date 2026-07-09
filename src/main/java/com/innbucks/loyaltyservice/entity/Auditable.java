package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Base for entities that carry a full audit trail — created/updated timestamps
 * AND the acting principal — auto-populated by Spring Data JPA auditing
 * ({@code @EnableJpaAuditing} + the {@code AuditorAware} in
 * {@code JpaAuditingConfig}).
 *
 * <p>Applied to the admin-configurable entities (Merchant, Shop, LoyaltyRule,
 * Campaign, VoucherTemplate, Tenant) whose create/update flows go through
 * authenticated POST/PUT endpoints, so "who created / last edited this settings
 * row, and when" is recorded. Transactional / ledger / derived rows
 * (LoyaltyTransaction, PointLot, Wallet, Voucher, Invoice, …) are deliberately
 * NOT audited this way — they are system-written and already carry their own
 * domain timestamps / actor (e.g. Voucher.issuerUserId, transactions.userId).
 *
 * <p>Timestamps are {@link Instant} (inherently UTC), so no custom
 * {@code DateTimeProvider} is needed. {@code createdAt} keeps a construction-time
 * initializer as a pre-persist fallback; auditing overwrites it at INSERT.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public abstract class Auditable {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * The acting principal's stable {@code user_uuid} (or the JWT subject/email
     * when a token carries no uuid claim) at INSERT — never updated. Null for
     * legacy rows and any system / unauthenticated write.
     */
    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    /** The acting principal on the last UPDATE (set equal to {@code createdBy}
     *  on INSERT). */
    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    private String updatedBy;
}
