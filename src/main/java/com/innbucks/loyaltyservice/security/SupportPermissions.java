package com.innbucks.loyaltyservice.security;

import java.util.regex.Pattern;

/**
 * The customer-support permissions loyalty understands, as they arrive in the
 * JWT {@code perms} claim and become bare authorities (no {@code ROLE_} prefix).
 *
 * <p>The vocabulary belongs to user-service's {@code PermissionCatalog}
 * (ticketing-system): a permission is added there, granted to the built-in
 * roles by a user-service migration, and reaches SUPER_ADMIN through its
 * {@code *} wildcard, which user-service expands at mint time. These strings
 * must stay character-identical to that catalog. Nothing but this comment
 * couples the two repositories; a drift presents as every agent getting a 403.
 *
 * <p>All four are PLATFORM scope. Support sees a customer across every tenant,
 * so no support endpoint reads {@code X-Tenant-Id} or {@link TenantContext}.
 *
 * <p>The {@code @PreAuthorize} expressions are built from these constants by
 * compile-time concatenation, so a typo here and a typo in a controller cannot
 * disagree.
 */
public final class SupportPermissions {

    private SupportPermissions() {}

    /** Look up a customer across every tenant, read their record, notes and messages. */
    public static final String READ = "loyalty-support:read";

    /** Routine actions: notes, resend a voucher to its holder, sign the customer out. */
    public static final String MANAGE = "loyalty-support:manage";

    /** Adjust/reverse points (existing caps), unblock a membership, review every agent. */
    public static final String SUPERVISE = "loyalty-support:supervise";

    /** Type and send an SMS/WhatsApp to a customer on record. Shared with marketplace. */
    public static final String SEND_MESSAGES = "customer-messages:send";

    public static final String HAS_READ = "hasAuthority('" + READ + "')";
    public static final String HAS_MANAGE = "hasAuthority('" + MANAGE + "')";
    public static final String HAS_SUPERVISE = "hasAuthority('" + SUPERVISE + "')";
    public static final String HAS_SEND_MESSAGES = "hasAuthority('" + SEND_MESSAGES + "')";

    /**
     * The shape of a permission code: lowercase, colon-namespaced,
     * {@code area:verb} or deeper. Load-bearing — see
     * {@link JwtFilter#permissionAuthorities}. It is what stops a {@code perms}
     * entry reading {@code ROLE_SUPER_ADMIN} (or {@code SERVICE_LOYALTY-OTP},
     * {@code VERIFIED}, {@code TIER_4}, {@code *}) becoming an authority that a
     * role or scope check would honour: none of those can match it.
     */
    public static final Pattern CODE_SHAPE = Pattern.compile("^[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*)+$");
}
