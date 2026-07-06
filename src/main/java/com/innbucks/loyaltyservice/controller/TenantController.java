package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/loyalty/tenants")
@Tag(name = "Tenants",
     description = "Top-level platform tenants — the white-label customers of the loyalty platform. " +
                   "These endpoints are operator-level: they do NOT require an X-Tenant-Id header, " +
                   "since they're how tenants are first registered.")
public class TenantController {

    private final TenantService tenantService;
    private final com.innbucks.loyaltyservice.service.TenantCachedLookup tenantLookup;

    public TenantController(TenantService tenantService,
                            com.innbucks.loyaltyservice.service.TenantCachedLookup tenantLookup) {
        this.tenantService = tenantService;
        this.tenantLookup = tenantLookup;
    }

    /**
     * Membership gate for the operator-level tenant endpoints that take the
     * tenant as a PATH id (so {@link com.innbucks.loyaltyservice.security.TenantContext},
     * which keys off the X-Tenant-Id header, doesn't apply). Mirrors
     * TenantContext's rule exactly: SUPER_ADMIN acts across every tenant; any
     * other caller must be a MEMBER of the path tenant, resolved dual-mode by
     * the stable {@code userId} claim OR the email (legacy rows). A caller who
     * is neither is rejected 403 — this is what stops one tenant's admin from
     * enumerating another tenant's admin roster.
     */
    private void requireMemberOrSuperAdmin(UUID tenantId,
            org.springframework.security.core.Authentication authentication) {
        if (authentication != null) {
            for (var granted : authentication.getAuthorities()) {
                if ("ROLE_SUPER_ADMIN".equals(granted.getAuthority())) {
                    return;
                }
            }
        }
        UUID userId = com.innbucks.loyaltyservice.security.CallerDetails.currentUserId();
        String email = authentication != null ? authentication.getName() : null;
        boolean member = (userId != null && tenantLookup.isMemberByUserId(tenantId, userId))
                || (email != null && !email.isBlank() && tenantLookup.isMember(tenantId, email));
        if (!member) {
            log.warn("Tenant roster access denied tenantId={} userId={} caller={}", tenantId, userId, email);
            throw com.innbucks.loyaltyservice.exception.LoyaltyException.forbidden(
                    "NOT_TENANT_MEMBER", "you are not a member of this tenant");
        }
    }

    @PostMapping
    @Operation(summary = "Register a new tenant",
            description = "Onboards a new tenant onto the platform AND attaches the user named by the request's " +
                          "`id` (the user's UUID) as the tenant's first member, in one call — there is no " +
                          "separate join step. That user can immediately pass the returned `id` in the " +
                          "`X-Tenant-Id` header. Operator-only — no tenant header required.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Tenant created (and the supplied user attached as a member)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Tenant created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Tenant created successfully",
                                      "data": {
                                        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "code": "innbucks",
                                        "name": "Innbucks",
                                        "status": "ACTIVE"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (missing id, blank code/name, or duplicate code)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "code: must not be blank",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "A tenant with this name already exists",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Duplicate name", value = """
                                    {
                                      "code": "TENANT_NAME_TAKEN",
                                      "message": "A tenant with that name already exists.",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TenantResponse>> create(
            @Valid @RequestBody Dtos.TenantRequest req,
            org.springframework.security.core.Authentication authentication) {
        String creatorEmail = authentication.getName();
        log.info("POST /loyalty/tenants creator={} code={}", creatorEmail, req.code());
        Dtos.TenantResponse data = tenantService.create(req, creatorEmail);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Tenant created successfully", data));
    }

    @GetMapping
    @Operation(summary = "List all tenants",
            description = "Returns every customer tenant on the platform. Intended for operator dashboards. " +
                          "The platform-internal ticketing container tenant (loyalty infrastructure for " +
                          "event-organizer merchants) is excluded. Operator-only — no tenant header required.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Tenants returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated tenants", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Tenants retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "code": "innbucks",
                                            "name": "Innbucks",
                                            "status": "ACTIVE"
                                          },
                                          {
                                            "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                            "code": "acme",
                                            "name": "Acme Coffee",
                                            "status": "SUSPENDED"
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 2,
                                        "totalPages": 1,
                                        "first": true,
                                        "last": true
                                      }
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','EVENT_ORGANIZER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<Dtos.TenantResponse>>> list(@ParameterObject Pageable pageable) {
        log.info("GET /loyalty/tenants (listing all tenants)");
        PageResponse<Dtos.TenantResponse> data = PageResponse.from(tenantService.list(pageable));
        return ResponseEntity.ok(ApiResult.ok("Tenants retrieved successfully", data));
    }

    @GetMapping("/me")
    @Operation(summary = "List tenants the current caller belongs to",
            description = "Returns every tenant the authenticated caller is a MEMBER of — matched by their " +
                          "user UUID (the id they were attached with at tenant registration) or, for legacy " +
                          "rows, their email. Use this right after login to discover which tenant UUIDs you " +
                          "can pass as X-Tenant-Id. The response is a list — a user can belong to several tenants.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Tenants owned by the caller",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "My tenants", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Tenants retrieved successfully",
                                      "data": [
                                        {
                                          "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "code": "ZW",
                                          "name": "Simbisa ZW",
                                          "status": "ACTIVE"
                                        }
                                      ]
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','EVENT_ORGANIZER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<java.util.List<Dtos.TenantResponse>>> mine(
            org.springframework.security.core.Authentication authentication) {
        UUID callerId = com.innbucks.loyaltyservice.security.CallerDetails.currentUserId();
        String email = authentication.getName();
        log.info("GET /loyalty/tenants/me userId={} email={}", callerId, email);
        java.util.List<Dtos.TenantResponse> data = tenantService.findMine(callerId, email);
        return ResponseEntity.ok(ApiResult.ok("Tenants retrieved successfully", data));
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend a tenant",
            description = "Marks the tenant as SUSPENDED. While suspended, all the tenant's customer-facing " +
                          "loyalty operations (earn, redeem, voucher issue/redeem) will be rejected. " +
                          "Use to halt activity during billing disputes or compliance reviews.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Tenant suspended",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Tenant suspended", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Tenant suspended successfully",
                                      "data": {
                                        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "code": "innbucks",
                                        "name": "Innbucks",
                                        "status": "SUSPENDED"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Tenant not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Tenant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TenantResponse>> suspend(@PathVariable UUID id) {
        log.info("POST /loyalty/tenants/{}/suspend", id);
        Dtos.TenantResponse data = tenantService.suspend(id);
        return ResponseEntity.ok(ApiResult.ok("Tenant suspended successfully", data));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Reactivate a suspended tenant",
            description = "Reverses /suspend by setting status back to ACTIVE. Idempotent — safe to call " +
                          "on an already-active tenant.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Tenant activated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Tenant activated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Tenant activated successfully",
                                      "data": {
                                        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "code": "innbucks",
                                        "name": "Innbucks",
                                        "status": "ACTIVE"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Tenant not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Tenant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TenantResponse>> activate(@PathVariable UUID id) {
        log.info("POST /loyalty/tenants/{}/activate", id);
        Dtos.TenantResponse data = tenantService.activate(id);
        return ResponseEntity.ok(ApiResult.ok("Tenant activated successfully", data));
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "List tenant members",
            description = "Returns every user with membership of this tenant. Useful for showing a tenant's " +
                          "admin team. Membership is the gate for tenant-scoped actions (replaces the old " +
                          "single-owner model).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Members of the tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Member list", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Members retrieved successfully",
                                      "data": [
                                        {
                                          "id": "8f2c6a1d-3b4e-4f78-9c0a-1b2c3d4e5f60",
                                          "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "userId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                          "email": null,
                                          "joinedAt": "2026-05-08T08:14:30Z"
                                        },
                                        {
                                          "id": "9a3d7b2e-4c5f-5a89-ad1b-2c3d4e5f6071",
                                          "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "userId": null,
                                          "email": "ops@simbisa.co.zw",
                                          "joinedAt": "2026-05-08T09:02:15Z"
                                        }
                                      ]
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Caller is not a member of this tenant (and not a SUPER_ADMIN)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not a member", value = """
                                    {
                                      "code": "NOT_TENANT_MEMBER",
                                      "message": "you are not a member of this tenant",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Tenant not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "tenant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','EVENT_ORGANIZER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<java.util.List<Dtos.TenantMemberResponse>>> members(
            @PathVariable UUID id,
            org.springframework.security.core.Authentication authentication) {
        log.info("GET /loyalty/tenants/{}/members", id);
        // The admin roster is tenant-private. The role gate above only proves
        // the caller is *some* admin; this proves they belong to THIS tenant
        // (or are a SUPER_ADMIN). Without it any merchant/shop admin could
        // enumerate every tenant's roster (emails + user UUIDs) — OWASP A01.
        requireMemberOrSuperAdmin(id, authentication);
        java.util.List<Dtos.TenantMemberResponse> data = tenantService.listMembers(id);
        return ResponseEntity.ok(ApiResult.ok("Members retrieved successfully", data));
    }

    @DeleteMapping("/{id}/members/me")
    @Operation(summary = "Leave a tenant",
            description = "Removes the authenticated caller from the tenant's members. Idempotent — leaving " +
                          "a tenant the caller isn't in returns 200 without error.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Caller is no longer a member",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Left tenant", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Left tenant successfully",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Tenant not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "tenant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','EVENT_ORGANIZER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Void>> leave(
            @PathVariable UUID id,
            org.springframework.security.core.Authentication authentication) {
        String email = authentication.getName();
        UUID userId = com.innbucks.loyaltyservice.security.CallerDetails.currentUserId();
        log.info("DELETE /loyalty/tenants/{}/members/me caller={} userId={}", id, email, userId);
        // Dual-mode removal — drop the userId-keyed row and any legacy
        // email-keyed row so a caller who joined via either path can leave.
        tenantService.removeMember(id, userId, email);
        return ResponseEntity.ok(ApiResult.ok("Left tenant successfully", null));
    }
}
