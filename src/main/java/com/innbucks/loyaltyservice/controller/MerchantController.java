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
                          "single count × flat.\n\n" +
                          "**Overriding the tenant standard at onboarding.** By default a new merchant " +
                          "inherits every global (tenant-wide) rule: the earn rate, the earning floor " +
                          "(`minTransactionAmount`) and both voucher fee schedules. Supply the optional " +
                          "`loyaltyOverride` block to give this merchant its own terms in the same call — " +
                          "it creates the merchant's rule under the covers, so there is no second POST to " +
                          "`/loyalty/rules`. Every field inside it is optional and inherits the global rule " +
                          "when omitted, so you can override just the floor, just a fee, or the whole set. " +
                          "The created rule's id comes back as `loyaltyRuleId`; change the terms later with " +
                          "`POST /loyalty/rules` (and deactivate the old rule).\n\n" +
                          "**A merchant must be priced for ISSUING.** Creation is refused with " +
                          "`MERCHANT_ZERO_ISSUE_FEE` when the effective voucher-issue fee resolves to zero — no " +
                          "fee on the merchant, none on its rule, and no tenant standard — because such a " +
                          "merchant is billed nothing forever and nothing else surfaces it. Fix it by pricing " +
                          "the merchant (`loyaltyOverride.feeIssued`), publishing a tenant standard on a global " +
                          "rule, or passing `waiveFees: true` with a `waiveFeesReason` to onboard it free on " +
                          "purpose. The **redeem** side may be zero freely — billing only issuance is a normal " +
                          "arrangement. Waived merchants are listed by `GET /loyalty/merchants/fee-audit`.")
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
                                        "feeRedeemed": { "type": "FIXED",                 "fixed": 0.10, "percentage": 0   },
                                        "loyaltyRuleId": "d6e2f4a5-4567-8901-bcde-f01234567890",
                                        "feeWaived": false,
                                        "feeWaivedReason": null
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
                            examples = {
                                    @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "name: must not be blank",
                                      "data": null
                                    }
                                    """),
                                    @ExampleObject(name = "Nobody priced this merchant", value = """
                                    {
                                      "code": "MERCHANT_ZERO_ISSUE_FEE",
                                      "message": "This merchant would be billed nothing for issuing vouchers. Set a voucher-issue fee (loyaltyOverride.feeIssued), publish a tenant standard on a global rule, or pass waiveFees=true with waiveFeesReason to onboard it free on purpose.",
                                      "data": null
                                    }
                                    """),
                                    @ExampleObject(name = "Waiver with no reason", value = """
                                    {
                                      "code": "WAIVER_REASON_REQUIRED",
                                      "message": "waiveFeesReason is required when waiving fees",
                                      "data": null
                                    }
                                    """)}
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
    public ResponseEntity<ApiResult<Dtos.MerchantResponse>> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Inherit the tenant standard",
                                    description = "No fees, no override — the merchant follows every global rule. "
                                            + "Only valid when a global rule actually prices the issue side; "
                                            + "otherwise this is refused with MERCHANT_ZERO_ISSUE_FEE.",
                                    value = """
                                    {
                                      "name": "Innbucks Westgate",
                                      "category": "Coffee",
                                      "currency": "USD",
                                      "billingCycle": "MONTHLY"
                                    }
                                    """),
                            @ExampleObject(name = "Free on purpose",
                                    description = "Deliberately unbilled. The reason is mandatory and is what the "
                                            + "zero-fee audit shows months later.",
                                    value = """
                                    {
                                      "name": "Pilot Partner Cafe",
                                      "category": "Coffee",
                                      "waiveFees": true,
                                      "waiveFeesReason": "Pilot partner - free for the first quarter, revisit 2026-10"
                                    }
                                    """),
                            @ExampleObject(name = "Override the tenant standard",
                                    description = "Own earn rate, own earning floor, own voucher fees — created as "
                                            + "the merchant's rule in this same call.",
                                    value = """
                                    {
                                      "name": "Innbucks Westgate",
                                      "category": "Coffee",
                                      "currency": "USD",
                                      "billingCycle": "MONTHLY",
                                      "loyaltyOverride": {
                                        "transactionType": "PURCHASE",
                                        "pointsPerUnit": 2,
                                        "multiplier": 1,
                                        "maxPointsPerTxn": 500,
                                        "pocket": "MAIN",
                                        "minTransactionAmount": 5.00,
                                        "feeIssued":   { "type": "FIXED_PLUS_PERCENTAGE", "fixed": 0.30, "percentage": 2.5 },
                                        "feeRedeemed": { "type": "FIXED",                 "fixed": 0.15, "percentage": 0 }
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "Override only the earning floor",
                                    description = "Everything else — earn rate and both fees — keeps inheriting the "
                                            + "tenant standard.",
                                    value = """
                                    {
                                      "name": "Innbucks Avondale",
                                      "category": "Coffee",
                                      "loyaltyOverride": { "minTransactionAmount": 2.00 }
                                    }
                                    """),
                            @ExampleObject(name = "Legacy: fee on the merchant record",
                                    description = "Pre-override shape, still accepted. Prefer loyaltyOverride.",
                                    value = """
                                    {
                                      "name": "Innbucks Borrowdale",
                                      "feeIssued":   { "type": "PERCENTAGE", "fixed": 0, "percentage": 2.5 },
                                      "feeRedeemed": { "type": "FIXED",      "fixed": 0.10, "percentage": 0 }
                                    }
                                    """)
                    }))
            @Valid @RequestBody Dtos.MerchantRequest req) {
        Dtos.MerchantResponse data = merchants.create(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Merchant created successfully", data));
    }

    @GetMapping("/fee-audit")
    @Operation(summary = "Zero-fee audit — merchants we issue vouchers for free",
            description = "Every merchant in the tenant whose EFFECTIVE voucher-issue fee resolves to zero, "
                    + "with whether that was a deliberate waiver or an oversight. Resolution is the same "
                    + "merchant-rule -> merchant-record -> global-rule precedence the invoice uses, so this is "
                    + "what will actually be billed, not what someone typed.\n\n"
                    + "`unwaived` is the number that matters: merchants onboarded and never priced. Redemption "
                    + "being free is reported but never counted as a problem — only issuing is guarded.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Audit complete",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Two merchants never priced", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Zero-fee audit complete",
                                      "data": {
                                        "merchantsExamined": 12,
                                        "issuingForFree": 3,
                                        "waived": 1,
                                        "unwaived": 2,
                                        "merchants": [
                                          {
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "name": "Innbucks Westgate",
                                            "status": "ACTIVE",
                                            "waived": false,
                                            "waivedReason": null,
                                            "redeemsForFree": true
                                          },
                                          {
                                            "merchantId": "c5d1e3f4-3456-7890-bcde-f01234567890",
                                            "name": "Pilot Partner Cafe",
                                            "status": "ACTIVE",
                                            "waived": true,
                                            "waivedReason": "Pilot partner - free for the first quarter",
                                            "redeemsForFree": true
                                          }
                                        ]
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller may not audit this tenant")
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ZeroFeeAudit>> feeAudit() {
        return ResponseEntity.ok(ApiResult.ok("Zero-fee audit complete",
                merchants.auditZeroFeeMerchants(tenantContext.requireTenantId())));
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
