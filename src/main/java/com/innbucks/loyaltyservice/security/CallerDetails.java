package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.exception.LoyaltyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Extra claims pulled from the authenticated JWT. Attached to the request's
 * Authentication as {@code details} by {@link JwtFilter}.
 *
 * <p>{@code merchantId} is set for principals whose JWT carries the claim —
 * today that's SHOP_ADMIN and SHOP_USER. MERCHANT_ADMIN tokens do NOT carry a
 * merchantId; those callers supply it in the request body, and write endpoints
 * use {@link #resolveMerchantId(UUID)} to pick the right source.
 *
 * <p>{@code shopId} is set only for SHOP_ADMIN and SHOP_USER (cashiers and
 * outlet supervisors). It identifies the specific outlet the principal works
 * at, which lets shop-staff endpoints scope results to that outlet without
 * exposing other outlets of the same merchant chain.
 *
 * <p>{@code phoneNumber} is set for CUSTOMER tokens (and any other role that
 * carries the claim). It's the authoritative identifier for "the caller's
 * wallet" — used by P2P transfer to verify the request's {@code fromUserId}
 * actually belongs to the authenticated principal, so a logged-in user can't
 * drain someone else's balance.
 *
 * <p>{@code userId} is the caller's stable cross-service UUID (JWT {@code
 * userId} claim). It's the primary key for tenant membership — {@code
 * TenantContext} admits a caller when this UUID matches a {@code tenant_members}
 * row, falling back to the email (principal name) for legacy rows. Null on
 * tokens minted before the claim landed.
 */
public record CallerDetails(UUID merchantId, UUID shopId, String phoneNumber, UUID userId) {

    public static UUID currentMerchantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object details = auth.getDetails();
        if (details instanceof CallerDetails cd) return cd.merchantId();
        return null;
    }

    /** JWT shopId claim, or {@code null} when the token doesn't carry one. */
    public static UUID currentShopId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object details = auth.getDetails();
        if (details instanceof CallerDetails cd) return cd.shopId();
        return null;
    }

    /** JWT phone claim, or {@code null} when the token doesn't carry one. */
    public static String currentPhoneNumber() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object details = auth.getDetails();
        if (details instanceof CallerDetails cd) return cd.phoneNumber();
        return null;
    }

    /** The authenticated principal's email (the JWT subject / login name), or
     *  {@code null} when there is no authentication on the context. Used to
     *  stamp a human-readable issuer identity on issued vouchers. */
    public static String currentEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    /** JWT userId claim (caller's stable cross-service UUID), or {@code null}
     *  when the token doesn't carry one (legacy tokens). */
    public static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object details = auth.getDetails();
        if (details instanceof CallerDetails cd) return cd.userId();
        return null;
    }

    /** True iff the current authentication carries any of the given authorities
     *  (e.g. {@code "ROLE_SUPER_ADMIN"}). Null-safe: returns {@code false} when
     *  unauthenticated. Centralises role checks so callers never touch the
     *  {@link Authentication} (or its nullable {@code getAuthority()}) directly. */
    public static boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        java.util.Set<String> wanted = java.util.Set.of(roles);
        return auth.getAuthorities().stream()
                .anyMatch(a -> wanted.contains(a.getAuthority()));
    }

    /**
     * Returns the JWT merchantId claim if present, otherwise the supplied body value.
     * Throws BAD_REQUEST if neither is set. Used by write endpoints that need a
     * merchant scope and accept callers from both classes:
     * <ul>
     *   <li>SHOP_ADMIN — claim is set in the JWT, {@code bodyMerchantId} is ignored.</li>
     *   <li>MERCHANT_ADMIN — no claim, must supply the value in the request body.</li>
     * </ul>
     */
    public static UUID resolveMerchantId(UUID bodyMerchantId) {
        UUID merged = merchantIdOrBody(bodyMerchantId);
        if (merged == null) {
            throw LoyaltyException.badRequest("MERCHANT_REQUIRED",
                    "merchantId must be supplied in the request body when the caller's JWT carries no merchant scope");
        }
        return merged;
    }

    /**
     * Same merging logic as {@link #resolveMerchantId(UUID)} but returns {@code null}
     * when neither source supplies a value, instead of throwing. Used by endpoints
     * where a null merchantId is meaningful — rule/campaign/voucher-template creation
     * treats it as "tenant-wide".
     */
    public static UUID merchantIdOrBody(UUID bodyMerchantId) {
        UUID fromJwt = currentMerchantId();
        return fromJwt != null ? fromJwt : bodyMerchantId;
    }
}
