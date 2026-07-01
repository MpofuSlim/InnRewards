package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Membership of a user in a tenant. Replaces the single-owner model that
 * stored an {@code ownerEmail} on the tenant: any number of merchant admins
 * can now belong to the same tenant, each granted access by an entry here.
 *
 * <p>{@code TenantContext} treats membership as the gate for tenant-scoped
 * actions (SUPER_ADMIN still bypasses). Members are attached when a tenant is
 * registered ({@code POST /loyalty/tenants}, keyed by the user's UUID) and
 * removed via {@code DELETE /loyalty/tenants/{id}/members/me}.
 *
 * <p>Membership is keyed on {@code userId} (the caller's stable cross-service
 * UUID from the JWT {@code userId} claim). The legacy {@code email} column is
 * retained, nullable, so rows created before the UUID migration still admit
 * their owner via the email fallback in {@code TenantContext.verifyMembership}.
 */
@Entity
@Table(name = "tenant_members", uniqueConstraints = {
        @UniqueConstraint(name = "uk_tenant_member_tenant_email", columnNames = {"tenant_id", "email"}),
        @UniqueConstraint(name = "uk_tenant_member_tenant_user", columnNames = {"tenant_id", "user_id"})
}, indexes = {
        @Index(name = "idx_tenant_member_email", columnList = "email"),
        @Index(name = "idx_tenant_member_tenant", columnList = "tenant_id")
})
@Getter
@Setter
@NoArgsConstructor
public class TenantMember {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Stable cross-service UUID of the member (JWT {@code userId} claim).
     *  Null on legacy rows created before the UUID migration. */
    @Column(name = "user_id")
    private UUID userId;

    /** Legacy email key. Nullable since membership moved to {@code userId};
     *  retained for backward-compatible access by pre-migration rows. */
    @Column(length = 200)
    private String email;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();
}
