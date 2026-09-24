package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
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
 *   <li><b>MERCHANT_ADMIN</b> — an OWNER or ADMIN of an organization holding the
 *       loyalty product ({@link CallerDetails#currentOrganizationId()}). They may
 *       act on exactly the merchants whose {@link Merchant#getOrganizationId()
 *       organizationId} is that organization — every brand the business runs,
 *       and nobody else's.</li>
 * </ul>
 *
 * <p>Ownership used to be an email: {@code merchants.admin_email} had to equal
 * the caller's login. That made one column the ownership key, the notification
 * address and (through user-service's claim lookup) marketplace's seller
 * identity at once, and it had no way to let a colleague in. A merchant with no
 * organization (a pre-V51 row nobody has stamped) matches no caller at all and
 * is reachable by SUPER_ADMIN only — fail closed, never "anyone".
 */
@Component
public class MerchantAuthz {

    private final MerchantRepository merchants;
    private final ShopRepository shops;

    public MerchantAuthz(MerchantRepository merchants, ShopRepository shops) {
        this.merchants = merchants;
        this.shops = shops;
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

        if (CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN")) {
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

        // MERCHANT_ADMIN: ownership is the organization. Both sides must be
        // present — a null on either (a caller with no loyalty organization, or
        // an unstamped merchant) is a refusal, never a match.
        UUID callerOrganization = CallerDetails.currentOrganizationId();
        if (callerOrganization != null && callerOrganization.equals(merchant.getOrganizationId())) {
            return merchant;
        }
        throw notOwner();
    }

    /**
     * Enforces that the authenticated caller may read/act on the given shop.
     *
     * <p>A shop belongs to exactly one merchant within the tenant. Scoping model:
     * <ul>
     *   <li><b>SUPER_ADMIN</b> — any shop.</li>
     *   <li><b>SHOP_ADMIN / SHOP_USER</b> — only the shop in their JWT {@code shopId}
     *       claim (a cashier must not read a sibling outlet's customer data).</li>
     *   <li><b>MERCHANT_ADMIN</b> — any shop belonging to a merchant they administer
     *       (delegated to {@link #requireCallerAdministersMerchant}).</li>
     * </ul>
     *
     * @throws LoyaltyException 404 if the shop is not in this tenant; 403
     *         {@code NOT_SHOP_MEMBER} / {@code NOT_MERCHANT_OWNER} otherwise.
     */
    public Shop requireCallerAccessesShop(UUID tenantId, UUID shopId) {
        if (shopId == null) {
            throw LoyaltyException.badRequest("SHOP_REQUIRED", "shopId is required");
        }
        Shop shop = shops.findById(shopId)
                .filter(s -> s.getTenantId().equals(tenantId))
                .orElseThrow(() -> LoyaltyException.notFound("shop"));

        if (CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN")) {
            return shop;
        }

        // SHOP_ADMIN / SHOP_USER: pinned to the single outlet in their token.
        UUID scopedShop = CallerDetails.currentShopId();
        if (scopedShop != null) {
            if (scopedShop.equals(shopId)) {
                return shop;
            }
            throw notShopMember();
        }

        // MERCHANT_ADMIN (no shop claim): allowed iff they administer the shop's
        // owning merchant. Reuses the merchant-ownership check (throws if not).
        requireCallerAdministersMerchant(tenantId, shop.getMerchantId());
        return shop;
    }

    private static LoyaltyException notOwner() {
        return LoyaltyException.forbidden("NOT_MERCHANT_OWNER",
                "You can only act on merchants you administer.");
    }

    private static LoyaltyException notShopMember() {
        return LoyaltyException.forbidden("NOT_SHOP_MEMBER",
                "You can only access shops you are assigned to.");
    }
}
