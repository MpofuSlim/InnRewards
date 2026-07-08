package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.MerchantService;
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
@RequestMapping("/loyalty/merchants")
@Tag(name = "Merchants",
     description = "Merchants are the brands/outlets that issue points and vouchers within a tenant. " +
                   "Each merchant has its own billing cycle and fee schedule (per-voucher-issued, " +
                   "per-voucher-redeemed) used by InvoicingService. Requires X-Tenant-Id.")
public class MerchantController {

    private final MerchantService merchants;
    private final TenantContext tenantContext;
    private final com.innbucks.loyaltyservice.security.MerchantAuthz merchantAuthz;

    public MerchantController(MerchantService merchants, TenantContext tenantContext,
                              com.innbucks.loyaltyservice.security.MerchantAuthz merchantAuthz) {
        this.merchants = merchants;
        this.tenantContext = tenantContext;
        this.merchantAuthz = merchantAuthz;
    }

    @PostMapping
    @Operation(summary = "Onboard a merchant",
            description = "Creates a new merchant outlet under the current tenant. The caller supplies the " +
                          "outlet's display name (e.g. \"Chicken Inn Westgate\") plus its category, currency, " +
                          "billing cycle, and the per-voucher fee schedules. One tenant can have many " +
                          "merchants — each outlet of a multi-location operator gets its own row. The " +
                          "merchant ID returned here is what callers reference in transaction / voucher / " +
                          "invoice requests.\n\n" +
                          "**Fee schedules.** `feeIssued` and `feeRedeemed` each independently configure " +
                          "how the merchant is billed when a voucher is issued or redeemed. Three modes are " +
                          "supported on each side:\n\n" +
                          "- `FIXED` — flat amount per voucher, independent of face value " +
                          "(`fee = fixed`).\n" +
                          "- `PERCENTAGE` — percentage of the voucher's face value " +
                          "(`fee = faceValue × percentage / 100`).\n" +
                          "- `FIXED_PLUS_PERCENTAGE` — both legs added together " +
                          "(`fee = fixed + faceValue × percentage / 100`).\n\n" +
                          "`percentage` is a **whole-number percent** — `2.5` means 2.5%. " +
                          "Omit `feeIssued` / `feeRedeemed` (or supply `null`) to default that side to " +
                          "FIXED 0 (no fee). The fee model is applied per voucher at invoice generation " +
                          "time, so a billing period accumulates as the sum of per-voucher fees, not a " +
                          "single count × flat.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Merchant created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Merchant created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Merchant created successfully",
                                      "data": {
                                        "id": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "name": "Innbucks Westgate",
                                        "category": "Coffee",
                                        "currency": "USD",
                                        "billingCycle": "MONTHLY",
                                        "status": "ACTIVE",
                                        "feeIssued":   { "type": "FIXED_PLUS_PERCENTAGE", "fixed": 0.30, "percentage": 2.5 },
                                        "feeRedeemed": { "type": "FIXED",                 "fixed": 0.10, "percentage": 0   }
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (e.g. blank name or invalid fee schedule)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "name: must not be blank",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "A merchant with that name already exists in this tenant (case-insensitive)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Name taken", value = """
                                    {
                                      "code": "MERCHANT_NAME_TAKEN",
                                      "message": "A merchant with that name already exists.",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.MerchantResponse>> create(@Valid @RequestBody Dtos.MerchantRequest req) {
        Dtos.MerchantResponse data = merchants.create(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Merchant created successfully", data));
    }

    @GetMapping
    @Operation(summary = "List merchants for the current tenant",
            description = "Returns every merchant belonging to the X-Tenant-Id tenant. Used by the " +
                          "tenant admin UI to populate merchant pickers. Pass `unassigned=true` to " +
                          "filter to merchants that do NOT yet have any MERCHANT_ADMIN user attached " +
                          "— used by the new-merchant-admin onboarding flow so the FE can show only " +
                          "yet-unclaimed merchants. The unassigned filter calls user-service; a 503 " +
                          "means user-service is unreachable and the caller should retry.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Merchants returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated merchants", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Merchants retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "name": "Innbucks Westgate",
                                            "category": "Coffee",
                                            "currency": "USD",
                                            "billingCycle": "MONTHLY",
                                            "status": "ACTIVE",
                                            "feeIssued":   { "type": "FIXED_PLUS_PERCENTAGE", "fixed": 0.30, "percentage": 2.5 },
                                            "feeRedeemed": { "type": "FIXED",                 "fixed": 0.10, "percentage": 0   }
                                          },
                                          {
                                            "id": "c5d1e3f4-3456-7890-abcd-ef0123456789",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "name": "Innbucks Sandton",
                                            "category": "Coffee",
                                            "currency": "USD",
                                            "billingCycle": "WEEKLY",
                                            "status": "INACTIVE",
                                            "feeIssued":   { "type": "PERCENTAGE", "fixed": 0,    "percentage": 1.5 },
                                            "feeRedeemed": { "type": "PERCENTAGE", "fixed": 0,    "percentage": 1.0 }
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
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<Dtos.MerchantResponse>>> list(
            @ParameterObject Pageable pageable,
            @RequestParam(value = "unassigned", defaultValue = "false") boolean unassigned) {
        try {
            PageResponse<Dtos.MerchantResponse> data = PageResponse.from(
                    merchants.list(tenantContext.requireTenantId(), pageable, unassigned));
            return ResponseEntity.ok(ApiResult.ok("Merchants retrieved successfully", data));
        } catch (IllegalStateException upstream) {
            // The unassigned filter needs user-service to identify the
            // exclusion set. Surface a 503 so the FE knows to retry — the
            // alternative (silently returning every merchant) would hand
            // the registering admin already-claimed merchants and defeat
            // the picker.
            log.warn("Unassigned-merchants lookup failed: {}", upstream.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResult.<PageResponse<Dtos.MerchantResponse>>builder()
                            .code("503 SERVICE_UNAVAILABLE")
                            .message("Could not determine assigned merchants; please retry")
                            .data(null)
                            .build());
        }
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate a merchant",
            description = "Sets status to ACTIVE. ACTIVE is the only state in which a merchant can earn " +
                          "or accept voucher redemptions. Idempotent.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Merchant activated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Merchant activated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Merchant activated successfully",
                                      "data": {
                                        "id": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "name": "Innbucks Westgate",
                                        "category": "Coffee",
                                        "currency": "USD",
                                        "billingCycle": "MONTHLY",
                                        "status": "ACTIVE",
                                        "feeIssued":   { "type": "FIXED_PLUS_PERCENTAGE", "fixed": 0.30, "percentage": 2.5 },
                                        "feeRedeemed": { "type": "FIXED",                 "fixed": 0.10, "percentage": 0   }
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Merchant not found in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Merchant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.MerchantResponse>> activate(@PathVariable UUID id) {
        UUID tenantId = tenantContext.requireTenantId();
        merchantAuthz.requireCallerAdministersMerchant(tenantId, id);
        Dtos.MerchantResponse data = merchants.setActive(tenantId, id, true);
        return ResponseEntity.ok(ApiResult.ok("Merchant activated successfully", data));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a merchant",
            description = "Sets status to INACTIVE. Inactive merchants will reject earn-points transactions " +
                          "with MERCHANT_INACTIVE. Existing wallets and unspent vouchers are preserved.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Merchant deactivated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Merchant deactivated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Merchant deactivated successfully",
                                      "data": {
                                        "id": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "name": "Innbucks Westgate",
                                        "category": "Coffee",
                                        "currency": "USD",
                                        "billingCycle": "MONTHLY",
                                        "status": "INACTIVE",
                                        "feeIssued":   { "type": "FIXED_PLUS_PERCENTAGE", "fixed": 0.30, "percentage": 2.5 },
                                        "feeRedeemed": { "type": "FIXED",                 "fixed": 0.10, "percentage": 0   }
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Merchant not found in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Merchant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.MerchantResponse>> deactivate(@PathVariable UUID id) {
        UUID tenantId = tenantContext.requireTenantId();
        merchantAuthz.requireCallerAdministersMerchant(tenantId, id);
        Dtos.MerchantResponse data = merchants.setActive(tenantId, id, false);
        return ResponseEntity.ok(ApiResult.ok("Merchant deactivated successfully", data));
    }
}
