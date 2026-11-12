package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Two tenants that accept each other's vouchers at the merchants each side puts
 * in (V68). One tenant invites, the other accepts or declines; either side can
 * end it; a SUPER_ADMIN can suspend it. See {@code VoucherPartnershipService}.
 */
@Entity
@Table(name = "voucher_partnerships")
@Getter
@Setter
@NoArgsConstructor
public class VoucherPartnership {

    public enum Status {
        /** Waiting for the invited tenant. */
        INVITED,
        /** Both sides agreed: vouchers may name the other side's merchants. */
        ACTIVE,
        /** Stopped by InnBucks: no new vouchers name the other side, and none is used there. */
        SUSPENDED,
        /** The invited tenant said no. */
        DECLINED,
        /** Either side ended it. Vouchers already sold stay usable until they expire. */
        ENDED;

        public boolean live() {
            return this == INVITED || this == ACTIVE || this == SUSPENDED;
        }
    }

    @Id
    private UUID id;

    @Column(name = "inviter_tenant_id", nullable = false, updatable = false)
    private UUID inviterTenantId;

    @Column(name = "invitee_tenant_id", nullable = false, updatable = false)
    private UUID inviteeTenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "auto_accepted", nullable = false)
    private boolean autoAccepted;

    @Column(name = "invited_by", nullable = false, length = 254, updatable = false)
    private String invitedBy;

    @Column(name = "invited_on_behalf", nullable = false, updatable = false)
    private boolean invitedOnBehalf;

    @Column(name = "invited_at", nullable = false, updatable = false)
    private Instant invitedAt;

    @Column(name = "decided_by", length = 254)
    private String decidedBy;

    @Column(name = "decided_on_behalf")
    private Boolean decidedOnBehalf;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "ended_by", length = 254)
    private String endedBy;

    @Column(name = "ended_on_behalf")
    private Boolean endedOnBehalf;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "suspended_by", length = 254)
    private String suspendedBy;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;

    @Version
    private long version;

    /** The other tenant, seen from {@code tenantId}'s side. */
    public UUID otherTenant(UUID tenantId) {
        return inviterTenantId.equals(tenantId) ? inviteeTenantId : inviterTenantId;
    }

    public boolean involves(UUID tenantId) {
        return inviterTenantId.equals(tenantId) || inviteeTenantId.equals(tenantId);
    }
}
