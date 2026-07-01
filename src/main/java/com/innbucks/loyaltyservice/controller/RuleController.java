package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.RuleAdminService;
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
@RequestMapping("/loyalty/rules")
@Tag(name = "Rules & Campaigns",
     description = "Earn-rate configuration. **Rules** define how many points are awarded per currency unit " +
                   "for each transaction type (PURCHASE, CARD_PAYMENT, QR_PAY, etc.) and may include caps " +
                   "and per-pocket targeting. **Campaigns** layer time-bound multipliers on top of rules " +
                   "(e.g. 2x weekend). Both are evaluated by RulesEngine on every transaction. Requires " +
                   "X-Tenant-Id.")
public class RuleController {

    private final RuleAdminService rules;
    private final TenantContext tenantContext;

    public RuleController(RuleAdminService rules, TenantContext tenantContext) {
        this.rules = rules;
        this.tenantContext = tenantContext;
    }

    @PostMapping
    @Operation(summary = "Create a loyalty rule",
            description = "Creates an earn-rate rule. **Two tiers of rules are supported — global and " +
                          "merchant-specific — and merchant-specific rules always win when both exist for " +
                          "the same transaction type.** " +
                          "TENANT_ADMIN (or PLATFORM_ADMIN / SUPER_ADMIN) tokens carry no merchantId, so " +
                          "the rule is created as a **global baseline** that applies to every merchant in " +
                          "the tenant that has no override. MERCHANT_ADMIN tokens carry the merchantId " +
                          "they manage, creating a **merchant-specific override** that supersedes the " +
                          "global rule for that outlet only. `pointsPerUnit` × `multiplier` is applied to " +
                          "the transaction amount; `maxPointsPerTxn` caps the result if set.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Rule created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Rule created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Rule created successfully",
                                      "data": {
                                        "id": "d6e2f4a5-4567-8901-bcde-f01234567890",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "transactionType": "PURCHASE",
                                        "pointsPerUnit": 1.000000,
                                        "multiplier": 1.0000,
                                        "maxPointsPerTxn": 500.0000,
                                        "pocket": "MAIN",
                                        "active": true,
                                        "startsAt": "2026-05-04T00:00:00Z",
                                        "endsAt": null,
                                        "createdAt": "2026-05-04T10:15:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (missing required fields)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "transactionType: must not be null",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<LoyaltyRule>> create(@Valid @RequestBody Dtos.RuleRequest req) {
        // Rule creation accepts a null merchantId (= tenant-wide global rule), so we
        // use the non-throwing helper instead of resolveMerchantId.
        UUID merchantId = CallerDetails.merchantIdOrBody(req.merchantId());
        LoyaltyRule data = rules.createRule(tenantContext.requireTenantId(), merchantId, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Rule created successfully", data));
    }

    @GetMapping
    @Operation(summary = "List rules for the current tenant",
            description = "Returns every rule belonging to the tenant — both merchant-specific and tenant-wide. " +
                          "Useful to audit which earn rates are currently in effect.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Rules returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated rules", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Rules retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "d6e2f4a5-4567-8901-bcde-f01234567890",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "transactionType": "PURCHASE",
                                            "pointsPerUnit": 1.000000,
                                            "multiplier": 1.0000,
                                            "maxPointsPerTxn": 500.0000,
                                            "pocket": "MAIN",
                                            "active": true,
                                            "startsAt": "2026-05-01T00:00:00Z",
                                            "endsAt": null,
                                            "createdAt": "2026-05-01T08:00:00Z"
                                          },
                                          {
                                            "id": "e7f3a5b6-5678-9012-cdef-012345678901",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "merchantId": null,
                                            "transactionType": "QR_PAY",
                                            "pointsPerUnit": 2.000000,
                                            "multiplier": 1.0000,
                                            "maxPointsPerTxn": null,
                                            "pocket": "MAIN",
                                            "active": true,
                                            "startsAt": null,
                                            "endsAt": null,
                                            "createdAt": "2026-04-30T12:00:00Z"
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
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<LoyaltyRule>>> list(@ParameterObject Pageable pageable) {
        PageResponse<LoyaltyRule> data = PageResponse.from(
                rules.listRules(tenantContext.requireTenantId(), pageable));
        return ResponseEntity.ok(ApiResult.ok("Rules retrieved successfully", data));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a rule",
            description = "Stops the rule from being applied to future transactions. Past transactions that " +
                          "earned points under this rule are unaffected. Use this rather than deletion so " +
                          "audit history (rule_id stamped on every transaction) remains valid. " +
                          "MERCHANT_ADMIN can only deactivate their own merchant-specific rules. " +
                          "TENANT_ADMIN (or higher) can deactivate both global and any merchant-specific rules.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Rule deactivated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Rule deactivated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Rule deactivated successfully",
                                      "data": {
                                        "id": "d6e2f4a5-4567-8901-bcde-f01234567890",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "transactionType": "PURCHASE",
                                        "pointsPerUnit": 1.000000,
                                        "multiplier": 1.0000,
                                        "maxPointsPerTxn": 500.0000,
                                        "pocket": "MAIN",
                                        "active": false,
                                        "startsAt": "2026-05-01T00:00:00Z",
                                        "endsAt": "2026-05-04T15:30:00Z",
                                        "createdAt": "2026-05-01T08:00:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Rule not found in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Rule not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<LoyaltyRule>> deactivate(@PathVariable UUID id) {
        // Use currentMerchantId rather than resolveMerchantId here: TENANT_ADMINs
        // legitimately deactivate global rules with no merchant scope, and we have
        // no body to read from on this endpoint.
        LoyaltyRule data = rules.deactivateRule(
                tenantContext.requireTenantId(), id, CallerDetails.currentMerchantId());
        return ResponseEntity.ok(ApiResult.ok("Rule deactivated successfully", data));
    }

    @PostMapping("/campaigns")
    @Operation(summary = "Launch a time-bound campaign",
            description = "Creates a campaign that multiplies points earned during the [startsAt, endsAt] " +
                          "window. RulesEngine picks the highest-multiplier active campaign per transaction. " +
                          "Scope to a merchant (`merchantId` set) or the whole tenant.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Campaign created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Campaign created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Campaign created successfully",
                                      "data": {
                                        "id": "f8a4b6c7-6789-0123-def0-123456789012",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "name": "Weekend 2x Points",
                                        "multiplier": 2.0000,
                                        "transactionType": "PURCHASE",
                                        "startsAt": "2026-05-04T00:00:00Z",
                                        "endsAt": "2026-05-31T23:59:59Z",
                                        "active": true,
                                        "matchedTransactions": 0,
                                        "createdAt": "2026-05-04T09:30:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (missing or invalid window)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "endsAt must be after startsAt",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "A campaign with that name already exists for this (tenant, merchant) scope (case-insensitive)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Name taken", value = """
                                    {
                                      "code": "CAMPAIGN_NAME_TAKEN",
                                      "message": "A campaign with that name already exists.",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Campaign>> createCampaign(@Valid @RequestBody Dtos.CampaignRequest req) {
        // Campaigns, like rules, may be tenant-wide when no merchant scope is supplied.
        Campaign data = rules.createCampaign(tenantContext.requireTenantId(),
                CallerDetails.merchantIdOrBody(req.merchantId()), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Campaign created successfully", data));
    }

    @GetMapping("/campaigns")
    @Operation(summary = "List campaigns for the current tenant",
            description = "Returns past, current, and future campaigns. Filter client-side by " +
                          "`startsAt`/`endsAt` to find live ones.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Campaigns returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated campaigns", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Campaigns retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "f8a4b6c7-6789-0123-def0-123456789012",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "name": "Weekend 2x Points",
                                            "multiplier": 2.0000,
                                            "transactionType": "PURCHASE",
                                            "startsAt": "2026-05-04T00:00:00Z",
                                            "endsAt": "2026-05-31T23:59:59Z",
                                            "active": true,
                                            "matchedTransactions": 142,
                                            "createdAt": "2026-05-01T09:30:00Z"
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 1,
                                        "totalPages": 1,
                                        "first": true,
                                        "last": true
                                      }
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<Campaign>>> listCampaigns(@ParameterObject Pageable pageable) {
        PageResponse<Campaign> data = PageResponse.from(
                rules.listCampaigns(tenantContext.requireTenantId(), pageable));
        return ResponseEntity.ok(ApiResult.ok("Campaigns retrieved successfully", data));
    }
}
