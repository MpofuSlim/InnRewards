package com.innbucks.loyaltyservice.security;

/**
 * Who sets what merchants pay InnBucks for vouchers (owner decision,
 * 2026-11-12): holders of {@code loyalty-fees:manage}, as it arrives in the JWT
 * {@code perms} claim and becomes a bare authority, and SUPER_ADMIN.
 *
 * <p>Never a merchant: a fee is InnBucks' commercial term, so a merchant admin
 * that could set one could set its own to zero. Every write path that carries a
 * fee checks {@link #callerMaySetFees()} — the fee endpoints through
 * {@link #HAS_MANAGE}, merchant onboarding and rule creation in the services.
 *
 * <p>The code belongs to user-service's {@code PermissionCatalog}
 * (ticketing-system), PLATFORM scope and not wildcard-reserved, so a finance
 * role can hold it. The string must stay character-identical to that catalog.
 * The SUPER_ADMIN role is accepted as well as the code, so either repo can
 * deploy first: a SUPER_ADMIN token minted before the catalog knew the code
 * carries the role but not the code.
 */
public final class FeesPermissions {

    private FeesPermissions() {}

    /** Set a tenant's default voucher fees and any merchant's own. */
    public static final String MANAGE = "loyalty-fees:manage";

    public static final String HAS_MANAGE = "hasRole('SUPER_ADMIN') or hasAuthority('" + MANAGE + "')";

    /** The refusal every fee-carrying write gives a caller without the authority. */
    public static final String REFUSED_CODE = "FEES_PLATFORM_ONLY";

    public static final String REFUSED_MESSAGE =
            "Fees are set by InnBucks. Leave the fee fields out and InnBucks will set this merchant's fees.";

    /** True iff the current caller may set fees. */
    public static boolean callerMaySetFees() {
        return CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN", MANAGE);
    }

    /** True iff the caller is platform staff reading fees: may see who set them. */
    public static boolean callerSeesFeeAuthors() {
        return callerMaySetFees() || ReportsPermissions.holdsRead();
    }
}
