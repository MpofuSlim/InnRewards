package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * Email of the user (from user-service) who created/owns this tenant.
     * Used by {@code TenantContext} to enforce that only the owner — or a
     * SUPER_ADMIN — can act on this tenant. Nullable for legacy rows created
     * before the ownership column existed.
     */
    @Column(name = "owner_email", length = 200)
    private String ownerEmail;

    public enum Status { ACTIVE, SUSPENDED, INACTIVE }
}
