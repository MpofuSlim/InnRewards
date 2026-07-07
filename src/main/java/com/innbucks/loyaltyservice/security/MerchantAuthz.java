package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Object-level authorization for merchant-scoped actions.
 *
 * <p>Tenant membership ({@link TenantContext}) proves the caller belongs to the
 * tenant, but NOT that they may act on a specific merchant within it — a tenant
 * can hold many merchants owned by different admins. Every endpoint that takes a
 * {@code merchantId} from the path or request body MUST run it through
 * {@link #requireCallerAdministersMerchant} so one merchant admin can't read or
 * mutate a sibling merchant's data (billing, points, vouchers, QR minting).
 *
 * <p>Ownership model (matches "a merchant-admin is in charge only of the
 * merchants they created"):
 * <ul>
 *   <li><b>SUPER_ADMIN</b> — platform operator; may act on any merchant.</li>
 *   <li><b>SHOP_ADMIN / SHOP_USER</b> — their JWT carries a {@code merchantId}
 *       claim scoping them to exactly one merchant; any other merchant is denied.</li>
 *   <li><b>MERCHANT_ADMIN</b> — no merchant claim in the token; ownership is the
 *       {@link Merchant#getAdminEmail() adminEmail} stamped from the creator's
 *       JWT subject at merchant-create time. The caller may act only on merchants
 *       whose {@code adminEmail} equals their own email (case-insensitive).</li>
 * </ul>
 *
 * <p>This deliberately reuses the existing {@code adminEmail} column and the
 * caller's email/merchant claims, so it needs no new JWT claim and no front-end
 * change.
 */
@Component
public class MerchantAuthz {

    private final MerchantRepository merchants;

    public MerchantAuthz(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    /**
     * Loads {@code merchantId}, confirms it lives in {@code tenantId}, and enforces
     * that the authenticated caller is authorised to act on it.
     *
     * @return the resolved {@link Merchant} (already tenant-checked) so callers can
     *         reuse it without a second lookup.
     * @throws LoyaltyException 404 {@code NOT_FOUND} if the merchant does not exist
     *         in this tenant; 403 {@code NOT_MERCHANT_OWNER} if the caller may not
     *         act on it.
     */
    public Merchant requireCallerAdministersMerchant(UUID tenantId, UUID merchantId) {
        if (merchantId == null) {
            throw LoyaltyException.badRequest("MERCHANT_REQUIRED", "merchantId is required");
        }
        // Tenant-scope the lookup: a merchant in another tenant is treated as
        // absent (404), never leaked as a 403 that would confirm its existence.
        Merchant merchant = merchants.findById(merchantId)
                .filter(m -> m.getTenantId().equals(tenantId))
                .orElseThrow(() -> LoyaltyException.notFound("merchant"));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (hasRole(auth, "ROLE_SUPER_ADMIN")) {
            return merchant;
        }

        // SHOP_ADMIN / SHOP_USER are pinned to the single merchant in their token.
        UUID scopedMerchant = CallerDetails.currentMerchantId();
        if (scopedMerchant != null) {
            if (scopedMerchant.equals(merchantId)) {
                return merchant;
            }
            throw notOwner();
        }

        // MERCHANT_ADMIN: ownership is the adminEmail stamped at create time.
        // equalsIgnoreCase is null-safe on its argument, so a single guard on the
        // receiver suffices (no redundant caller-email null-check to flag).
        String callerEmail = CallerDetails.currentEmail();
        String ownerEmail = merchant.getAdminEmail();
        if (ownerEmail != null && ownerEmail.equalsIgnoreCase(callerEmail)) {
            return merchant;
        }
        throw notOwner();
    }

    private static LoyaltyException notOwner() {
        return LoyaltyException.forbidden("NOT_MERCHANT_OWNER",
                "You can only act on merchants you administer.");
    }

    private static boolean hasRole(Authentication auth, String role) {
        // auth is non-null here: every caller resolves the tenant via TenantContext
        // first, which rejects unauthenticated requests. Mirrors TenantContext.hasRole
        // (no redundant null-check for Qodana to flag).
        return auth.getAuthorities().stream()
                .anyMatch(a -> role.equals(a.getAuthority()));
    }
}
