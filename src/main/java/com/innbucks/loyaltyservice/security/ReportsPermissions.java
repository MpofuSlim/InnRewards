package com.innbucks.loyaltyservice.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Read-only platform access: {@code loyalty-reports:read}, as it arrives in the
 * JWT {@code perms} claim and becomes a bare authority (no {@code ROLE_} prefix).
 *
 * <p>Its holder reads what SUPER_ADMIN reads, in every tenant and merchant, and
 * changes nothing. A developer account holding {@code PRODUCT_OFFICER} is the
 * first holder: Ticketize already treats that role as a read-only platform
 * reader, and loyalty had no read-only platform role at all (TENANT_ADMIN and
 * PLATFORM_ADMIN can write). So it is a permission, not a role.
 *
 * <p>The vocabulary belongs to user-service's {@code PermissionCatalog}
 * (ticketing-system), where this code is PLATFORM scope. The string must stay
 * character-identical to that catalog; a drift presents as every reader getting
 * a 403, never as a reader getting more.
 *
 * <p>"Read-only" is held in three places, and each fails on its own:
 * <ul>
 *   <li>only GET handlers name {@link #HAS_READ} in their {@code @PreAuthorize}
 *       ({@code ReadOnlyPlatformAccessTest} walks every non-GET handler and
 *       fails on one that does);</li>
 *   <li>the scope helpers ({@link TenantContext}, {@link MerchantAuthz}) widen a
 *       holder's reach only on a GET or HEAD request
 *       ({@link #readsOnThisRequest()}), so a write endpoint that ever let the
 *       permission in would still meet every membership and ownership check;</li>
 *   <li>the write-side checks ({@code requireCallerAdministersMerchant},
 *       {@code requireCallerAccessesShop}, and the services' own role lists) are
 *       untouched and refuse a holder whatever the method.</li>
 * </ul>
 *
 * <p>It never returns a voucher code: a code is a bearer credential, so a
 * reader who could see one could spend it. Endpoints that carry codes either
 * withhold them for a holder ({@link #codesWithheld()}) or do not admit it.
 */
public final class ReportsPermissions {

    private ReportsPermissions() {}

    /** Read every tenant's and merchant's loyalty figures; write nothing. */
    public static final String READ = "loyalty-reports:read";

    public static final String HAS_READ = "hasAuthority('" + READ + "')";

    /** The Swagger line every endpoint that admits {@link #READ} carries. */
    public static final String DOC = " Also `" + READ + "` (read-only platform access).";

    /** {@link #DOC} for an endpoint that carries voucher codes. */
    public static final String DOC_CODES_WITHHELD = DOC
            + " For that caller every voucher code in the response is null.";

    /** True iff the current caller holds {@link #READ}, whatever the request. */
    public static boolean holdsRead() {
        return CallerDetails.hasAnyRole(READ);
    }

    /**
     * True iff the caller holds {@link #READ} AND the current request is a GET
     * or HEAD. This is the one test the scope helpers widen on: never true on a
     * write, and never true outside a request (a job, a listener), where there
     * is no method to check.
     */
    public static boolean readsOnThisRequest() {
        return holdsRead() && isSafeMethod(currentMethod());
    }

    /** {@link #readsOnThisRequest()} for a caller that already holds the request. */
    public static boolean readsOnThisRequest(HttpServletRequest request) {
        return holdsRead() && request != null && isSafeMethod(request.getMethod());
    }

    /**
     * True when voucher codes must be left out of the response: the caller
     * holds {@link #READ} and is not SUPER_ADMIN. SUPER_ADMIN's {@code *}
     * wildcard is expanded into every catalog code at mint, so its token carries
     * {@link #READ} too, and the operator must keep seeing codes.
     *
     * <p>Any other holder loses every code, its own merchants' included, even if
     * it also held a role that could have seen some. user-service never mints
     * that combination (a PLATFORM code makes a staff role, and staff belong to
     * no business), so failing closed costs nothing real.
     */
    public static boolean codesWithheld() {
        return holdsRead() && !CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN");
    }

    static boolean isSafeMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private static String currentMethod() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest().getMethod();
        }
        return null;
    }
}
