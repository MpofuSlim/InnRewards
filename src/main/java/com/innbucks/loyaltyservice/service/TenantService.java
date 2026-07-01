package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.CacheConfig;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TenantMember;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.TenantMemberNotifier;
import com.innbucks.loyaltyservice.repository.TenantMemberRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TenantService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TenantService.class);

    private final TenantRepository tenants;
    private final TenantMemberRepository members;
    private final org.springframework.cache.CacheManager cacheManager;
    private final TenantMemberNotifier tenantMemberNotifier;

    public TenantService(TenantRepository tenants, TenantMemberRepository members,
                         org.springframework.cache.CacheManager cacheManager,
                         TenantMemberNotifier tenantMemberNotifier) {
        this.tenants = tenants;
        this.members = members;
        this.cacheManager = cacheManager;
        this.tenantMemberNotifier = tenantMemberNotifier;
    }

    public Dtos.TenantResponse create(Dtos.TenantRequest req) {
        return create(req, null);
    }

    /**
     * Creates a tenant AND attaches the user named by {@code req.id()} as its
     * first member, in one transaction. This folds the old
     * {@code POST /loyalty/tenants/{id}/join} step into registration: the
     * caller supplies the user's UUID and that user is granted access
     * immediately. The {@code creatorEmail} parameter is retained as audit
     * metadata (who provisioned this tenant) on {@code tenant.ownerEmail} but
     * no longer gates access — that's the job of {@link TenantMember} rows.
     */
    public Dtos.TenantResponse create(Dtos.TenantRequest req, String creatorEmail) {
        String name = req.name() == null ? null : req.name().trim();
        // Control measure: no two tenants may share a name (case-insensitive).
        // Service-level guard (not a DB unique index) so it doesn't fail to
        // deploy against any pre-existing duplicate rows.
        if (name != null && tenants.existsByNameIgnoreCase(name)) {
            throw LoyaltyException.conflict("TENANT_NAME_TAKEN",
                    "A tenant named \"" + name + "\" already exists.");
        }
        Tenant t = new Tenant();
        t.setCode(req.code());
        t.setName(name);
        t.setOwnerEmail(creatorEmail);
        tenants.save(t);
        // Attach the supplied user as the tenant's first member so they can
        // immediately pass this tenant's id as X-Tenant-Id.
        addMember(t.getId(), req.id());
        // Best-effort "you've been added to {tenant}" ping (WhatsApp → SMS).
        // @Async on its own thread, so it runs AFTER this txn commits and can
        // never delay or fail the 201. The notifier already swallows every
        // failure; the defensive try/catch here only guards the (unexpected)
        // case of the async hand-off itself throwing synchronously.
        try {
            tenantMemberNotifier.notifyAddedToTenant(req.id(), t.getName());
        } catch (RuntimeException e) {
            log.warn("Failed to dispatch tenant-attach notification for tenant={} userId={}: {}",
                    t.getId(), req.id(), e.toString());
        }
        return toResponse(t);
    }

    @Transactional(readOnly = true)
    public List<Dtos.TenantResponse> list() {
        // Hide the platform-internal ticketing container tenant from operator
        // listings — it's infrastructure, not a real customer tenant.
        return tenants.findByIdNot(TicketingLoyaltyService.TICKETING_TENANT_ID).stream()
                .map(TenantService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<Dtos.TenantResponse> list(Pageable pageable) {
        // Hide the platform-internal ticketing container tenant from operator
        // listings — excluded at the query level so pagination totals are correct.
        return tenants.findByIdNot(TicketingLoyaltyService.TICKETING_TENANT_ID, pageable)
                .map(TenantService::toResponse);
    }

    /**
     * Tenants the caller is a member of, resolved DUAL-MODE to mirror the
     * membership check: by the caller's stable {@code userId} (UUID-keyed rows —
     * e.g. a user attached at tenant registration, whose email column is null)
     * OR their {@code email} (legacy rows). Deduped by tenant. Without the
     * userId arm, users attached via the UUID flow never saw their tenant here
     * even though they could already use it via X-Tenant-Id.
     */
    @Transactional(readOnly = true)
    public List<Dtos.TenantResponse> findMine(UUID userId, String email) {
        java.util.LinkedHashSet<UUID> tenantIds = new java.util.LinkedHashSet<>();
        if (userId != null) {
            members.findByUserId(userId).forEach(m -> tenantIds.add(m.getTenantId()));
        }
        if (email != null && !email.isBlank()) {
            members.findByEmail(email).forEach(m -> tenantIds.add(m.getTenantId()));
        }
        return tenantIds.stream()
                // Never surface the platform-internal ticketing container tenant,
                // even if a membership row somehow points at it.
                .filter(id -> !TicketingLoyaltyService.TICKETING_TENANT_ID.equals(id))
                .map(id -> tenants.findById(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(TenantService::toResponse)
                .toList();
    }

    @CacheEvict(value = CacheConfig.CACHE_TENANTS, key = "#id")
    public Dtos.TenantResponse suspend(UUID id) {
        Tenant t = tenants.findById(id).orElseThrow(() -> LoyaltyException.notFound("tenant"));
        t.setStatus(Tenant.Status.SUSPENDED);
        return toResponse(t);
    }

    @CacheEvict(value = CacheConfig.CACHE_TENANTS, key = "#id")
    public Dtos.TenantResponse activate(UUID id) {
        Tenant t = tenants.findById(id).orElseThrow(() -> LoyaltyException.notFound("tenant"));
        t.setStatus(Tenant.Status.ACTIVE);
        return toResponse(t);
    }

    /**
     * Adds the user (by stable UUID) as a member of the tenant. Idempotent —
     * re-adding an already-attached user returns the existing membership
     * without error. The email column is left null; the row is keyed on
     * {@code userId}.
     */
    @CacheEvict(value = CacheConfig.CACHE_TENANT_MEMBERSHIP, key = "#tenantId + ':u:' + #userId")
    public Dtos.TenantMemberResponse addMember(UUID tenantId, UUID userId) {
        Tenant t = tenants.findById(tenantId).orElseThrow(() -> LoyaltyException.notFound("tenant"));
        return members.findByTenantIdAndUserId(t.getId(), userId)
                .map(TenantService::toMemberResponse)
                .orElseGet(() -> {
                    TenantMember m = new TenantMember();
                    m.setTenantId(t.getId());
                    m.setUserId(userId);
                    members.save(m);
                    return toMemberResponse(m);
                });
    }

    @Transactional(readOnly = true)
    public List<Dtos.TenantMemberResponse> listMembers(UUID tenantId) {
        if (!tenants.existsById(tenantId)) throw LoyaltyException.notFound("tenant");
        return members.findByTenantId(tenantId).stream()
                .map(TenantService::toMemberResponse)
                .toList();
    }

    /**
     * Removes the caller from the tenant. Dual-mode to mirror the membership
     * check: deletes the {@code userId}-keyed row when a UUID is present, and
     * always also clears any legacy email-keyed row, so a caller whose access
     * came through either path can leave. Idempotent. Evicts both possible
     * membership-cache keys directly (an annotation can't target two keys).
     */
    public void removeMember(UUID tenantId, UUID userId, String email) {
        if (!tenants.existsById(tenantId)) throw LoyaltyException.notFound("tenant");
        if (userId != null) {
            members.deleteByTenantIdAndUserId(tenantId, userId);
            evictMembership(tenantId + ":u:" + userId);
        }
        if (email != null && !email.isBlank()) {
            members.deleteByTenantIdAndEmail(tenantId, email);
            evictMembership(tenantId + ":" + email);
        }
    }

    private void evictMembership(String key) {
        var cache = cacheManager.getCache(CacheConfig.CACHE_TENANT_MEMBERSHIP);
        if (cache != null) cache.evict(key);
    }

    public static Dtos.TenantResponse toResponse(Tenant t) {
        return new Dtos.TenantResponse(t.getId(), t.getCode(), t.getName(), t.getStatus().name());
    }

    public static Dtos.TenantMemberResponse toMemberResponse(TenantMember m) {
        return new Dtos.TenantMemberResponse(m.getId(), m.getTenantId(), m.getUserId(),
                m.getEmail(), m.getJoinedAt());
    }
}
