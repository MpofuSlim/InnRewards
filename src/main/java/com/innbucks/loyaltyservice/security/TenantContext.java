package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.service.TenantCachedLookup;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.UUID;

/**
 * Multi-tenant resolver. The API gateway forwards either an X-Tenant-Id (UUID)
 * or X-Tenant-Code header. Any request that hits a tenant-aware endpoint
 * without a valid tenant header is rejected.
 *
 * <p>Membership is enforced once per request in {@link #requireTenant()}: the
 * authenticated principal must be a member of the tenant (row in
 * {@code tenant_members}), unless they hold the SUPER_ADMIN role — SUPER_ADMIN
 * acts across every tenant on the platform without needing membership rows.
 *
 * <p>Members are attached when a tenant is registered ({@code POST
 * /loyalty/tenants}, keyed by the user's UUID) and removed via
 * {@code DELETE /loyalty/tenants/{id}/members/me}. This replaces the older
 * single-owner model where {@code tenant.ownerEmail} gated access.
 *
 * <p>The membership check is dual-mode: a caller is admitted when their JWT
 * {@code userId} matches a {@code tenant_members} row, OR (fallback) when their
 * email (the principal name) matches a legacy row created before membership
 * moved to UUIDs. Either match grants access.
 */
@Component
@RequestScope
@Slf4j
public class TenantContext {

    private final TenantRepository tenants;
    private final TenantCachedLookup lookup;
    private final HttpServletRequest request;
    private Tenant cached;

    public TenantContext(TenantRepository tenants, TenantCachedLookup lookup, HttpServletRequest request) {
        this.tenants = tenants;
        this.lookup = lookup;
        this.request = request;
    }

    public Tenant requireTenant() {
        if (cached != null) return cached;
        Tenant resolved = resolveFromHeaders();
        verifyMembership(resolved);
        cached = resolved;
        return cached;
    }

    public UUID requireTenantId() {
        return requireTenant().getId();
    }

    private Tenant resolveFromHeaders() {
        String idHeader = request.getHeader("X-Tenant-Id");
        if (idHeader != null && !idHeader.isBlank()) {
            try {
                // Cached lookup — backed by Caffeine via TenantCachedLookup.
                return lookup.findById(UUID.fromString(idHeader.trim()))
                        .orElseThrow(() -> LoyaltyException.notFound("tenant"));
            } catch (IllegalArgumentException ex) {
                throw LoyaltyException.badRequest("BAD_TENANT", "X-Tenant-Id is not a valid UUID");
            }
        }
        String codeHeader = request.getHeader("X-Tenant-Code");
        if (codeHeader != null && !codeHeader.isBlank()) {
            java.util.List<Tenant> matches = tenants.findAllByCode(codeHeader.trim());
            if (matches.isEmpty()) throw LoyaltyException.notFound("tenant");
            if (matches.size() > 1) throw LoyaltyException.badRequest("AMBIGUOUS_TENANT",
                    "Multiple tenants share code '" + codeHeader.trim() + "'. Use X-Tenant-Id instead.");
            return matches.get(0);
        }
        // Log the method + URI + caller so a "MISSING_TENANT" in prod tells you
        // exactly which frontend call dropped the header instead of leaving you
        // to guess from the error message alone.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String caller = (auth != null && auth.getName() != null) ? auth.getName() : "anonymous";
        log.warn("MISSING_TENANT method={} uri={} caller={} origin={}",
                request.getMethod(), request.getRequestURI(), caller, request.getHeader("Origin"));
        throw LoyaltyException.badRequest("MISSING_TENANT", "X-Tenant-Id or X-Tenant-Code header is required");
    }

    private void verifyMembership(Tenant tenant) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            // Pre-auth code path (e.g. internal callers without a JWT). We
            // refuse rather than silently allow — every tenant-scoped call
            // must come through an authenticated principal.
            log.warn("Tenant access without authentication tenantId={}", tenant.getId());
            throw new AccessDeniedException("Authentication required to access tenant data");
        }
        if (hasRole(authentication, "ROLE_SUPER_ADMIN")) {
            return;
        }
        // Dual-mode membership: prefer the caller's stable UUID (JWT userId
        // claim); fall back to the email (principal name) so legacy members
        // whose rows predate the UUID migration — and tokens minted before the
        // userId claim landed — still get in.
        UUID userId = CallerDetails.currentUserId();
        String caller = authentication.getName();
        boolean member = (userId != null && lookup.isMemberByUserId(tenant.getId(), userId))
                || lookup.isMember(tenant.getId(), caller);
        if (!member) {
            log.warn("Tenant membership check failed tenantId={} userId={} caller={}",
                    tenant.getId(), userId, caller);
            throw new AccessDeniedException(
                    "You are not a member of this tenant.");
        }
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> role.equals(a.getAuthority()));
    }
}
