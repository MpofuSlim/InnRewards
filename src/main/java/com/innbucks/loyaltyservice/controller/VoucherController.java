package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.VoucherTemplateService;
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

import java.util.List;
import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/loyalty/vouchers")
@Tag(name = "Vouchers",
     description = "Voucher templates and individual voucher lifecycle. Each voucher carries an HMAC-SHA256 " +
                   "signature over its code (signed with `loyalty.voucher.secret`) so redemption can be " +
                   "verified offline if needed. Anti-fraud (duplicate, wrong-merchant, blocked-user, " +
                   "blocked-device, velocity) is enforced on every `/redeem`. Requires X-Tenant-Id.")
public class VoucherController {

    private final VoucherService voucherService;
    private final VoucherTemplateService templateService;
    private final TenantContext tenantContext;

    public VoucherController(VoucherService voucherService,
                             VoucherTemplateService templateService,
                             TenantContext tenantContext) {
        this.voucherService = voucherService;
        this.templateService = templateService;
        this.tenantContext = tenantContext;
    }

    @PostMapping("/templates")
    @Operation(summary = "Create a voucher template",
            description = "Defines the *kind* of voucher (SINGLE_USE / MULTI_USE / CAMPAIGN / REFERRAL / " +
                          "CORPORATE) and how its value is expressed (AMOUNT / PERCENT / FREE_ITEM / COMBO). " +
                          "Templates are reusable — actual vouchers are minted from them via /issue or /issue-bulk.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Template created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Template created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Voucher template created successfully",
                                      "data": {
                                        "id": "a9b5c7d8-7890-1234-ef01-234567890123",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "name": "$5 Off Coffee",
                                        "type": "SINGLE_USE",
                                        "valueType": "AMOUNT",
                                        "currency": "USD",
                                        "freeItemSku": null,
                                        "usageLimit": 1,
                                        "validityDays": 30,
                                        "applicableOutlets": [
                                          "11111111-aaaa-bbbb-cccc-222222222222",
                                          "33333333-dddd-eeee-ffff-444444444444"
                                        ],
                                        "active": true,
                                        "createdAt": "2026-05-04T10:00:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error",
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
                    description = "A voucher template with that name already exists for this (tenant, merchant) scope (case-insensitive)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Name taken", value = """
                                    {
                                      "code": "VOUCHER_TEMPLATE_NAME_TAKEN",
                                      "message": "A voucher template with that name already exists.",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<VoucherTemplate>> createTemplate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = Dtos.VoucherTemplateRequest.class),
                            examples = @ExampleObject(name = "Create template", value = """
                                    {
                                      "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                      "name": "$5 Off Your Next Coffee",
                                      "type": "SINGLE_USE",
                                      "valueType": "AMOUNT",
                                      "currency": "USD",
                                      "freeItemSku": null,
                                      "usageLimit": 1,
                                      "validityDays": 30,
                                      "applicableOutlets": [
                                        "11111111-aaaa-bbbb-cccc-222222222222",
                                        "33333333-dddd-eeee-ffff-444444444444"
                                      ]
                                    }
                                    """)))
            @Valid @RequestBody Dtos.VoucherTemplateRequest req) {
        // Templates may be tenant-wide (null merchantId) so use merchantIdOrBody.
        VoucherTemplate data = templateService.create(tenantContext.requireTenantId(),
                CallerDetails.merchantIdOrBody(req.merchantId()), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Voucher template created successfully", data));
    }

    @GetMapping("/templates")
    @Operation(summary = "List voucher templates",
            description = "Returns every template defined for the current tenant. Used by merchant dashboards " +
                          "to populate \"issue voucher\" pickers.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Templates returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated templates", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Voucher templates retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "a9b5c7d8-7890-1234-ef01-234567890123",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "name": "$5 Off Coffee",
                                            "type": "SINGLE_USE",
                                            "valueType": "AMOUNT",
                                            "currency": "USD",
                                            "freeItemSku": null,
                                            "usageLimit": 1,
                                            "validityDays": 30,
                                            "applicableOutlets": [
                                              "11111111-aaaa-bbbb-cccc-222222222222",
                                              "33333333-dddd-eeee-ffff-444444444444"
                                            ],
                                            "active": true,
                                            "createdAt": "2026-05-04T10:00:00Z"
                                          },
                                          {
                                            "id": "b0a6d8e9-8901-2345-f012-345678901234",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "name": "10% Off Pastries",
                                            "type": "CAMPAIGN",
                                            "valueType": "PERCENT",
                                            "currency": "USD",
                                            "freeItemSku": null,
                                            "usageLimit": 1,
                                            "validityDays": 14,
                                            "applicableOutlets": null,
                                            "active": true,
                                            "createdAt": "2026-04-30T09:00:00Z"
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
    public ResponseEntity<ApiResult<PageResponse<VoucherTemplate>>> listTemplates(@ParameterObject Pageable pageable) {
        PageResponse<VoucherTemplate> data = PageResponse.from(
                templateService.list(tenantContext.requireTenantId(), pageable));
        return ResponseEntity.ok(ApiResult.ok("Voucher templates retrieved successfully", data));
    }

    @PostMapping("/issue")
    @Operation(summary = "Issue a single voucher",
            description = "Mints one voucher from a template. Optionally assign it to a known LoyaltyUser " +
                          "(`assignedUserId`) or to an arbitrary phone (`assigneePhone`). Returns the signed " +
                          "voucher code that the customer presents at redemption. Delivery channel (SMS, " +
                          "WhatsApp, EMAIL, PUSH, POS, NONE) controls how NotificationGateway notifies the customer.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Voucher issued",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Voucher issued", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Voucher issued successfully",
                                      "data": {
                                        "id": "c1b7e9f0-9012-3456-0123-456789012345",
                                        "code": "VCH-AB12CD34",
                                        "status": "ISSUED",
                                        "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                        "assignedUserId": "d2c8f0a1-0123-4567-1234-567890123456",
                                        "assigneePhone": "+254700000000",
                                        "usesRemaining": 1,
                                        "valueType": "AMOUNT",
                                        "value": 5.0000,
                                        "currency": "USD",
                                        "issuedAt": "2026-05-04T10:30:00Z",
                                        "expiresAt": "2026-06-03T10:30:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "templateId: must not be null",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Template not found in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Voucher template not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.VoucherResponse>> issue(@Valid @RequestBody Dtos.IssueVoucherRequest req) {
        Dtos.VoucherResponse data = voucherService.issue(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Voucher issued successfully", data));
    }

    @PostMapping("/issue-bulk")
    @Operation(summary = "Bulk-issue vouchers from a template",
            description = "Mints `quantity` independent unassigned vouchers in one call (campaign / corporate / " +
                          "referral distributions). Each gets its own unique signed code. Use the returned " +
                          "`batchId` (via the codes' batch reference) to track the run.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Bulk vouchers issued",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Bulk issued", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Vouchers issued successfully",
                                      "data": [
                                        {
                                          "id": "c1b7e9f0-9012-3456-0123-456789012345",
                                          "code": "VCH-AB12CD34",
                                          "status": "ISSUED",
                                          "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                          "assignedUserId": null,
                                          "assigneePhone": null,
                                          "usesRemaining": 1,
                                          "valueType": "AMOUNT",
                                          "value": 5.0000,
                                          "currency": "USD",
                                          "issuedAt": "2026-05-04T10:30:00Z",
                                          "expiresAt": "2026-06-03T10:30:00Z"
                                        },
                                        {
                                          "id": "d2c8f0a1-0123-4567-1234-567890123456",
                                          "code": "VCH-EF56GH78",
                                          "status": "ISSUED",
                                          "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                          "assignedUserId": null,
                                          "assigneePhone": null,
                                          "usesRemaining": 1,
                                          "valueType": "AMOUNT",
                                          "value": 5.0000,
                                          "currency": "USD",
                                          "issuedAt": "2026-05-04T10:30:00Z",
                                          "expiresAt": "2026-06-03T10:30:00Z"
                                        }
                                      ]
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "quantity: must be at least 1",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Template not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Voucher template not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<List<Dtos.VoucherResponse>>> issueBulk(@Valid @RequestBody Dtos.BulkIssueRequest req) {
        List<Dtos.VoucherResponse> data = voucherService.issueBulk(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Vouchers issued successfully", data));
    }

    @PostMapping("/redeem")
    @Operation(summary = "Redeem a voucher at a merchant",
            description = "Validates the code's signature, expiry, status, and merchant scope; checks the " +
                          "device fingerprint / IP against velocity limits; and decrements `usesRemaining`. " +
                          "Failed attempts are recorded in `fraud_attempts` and may auto-block the user via " +
                          "FraudService when the velocity threshold is exceeded.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Voucher redeemed",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Redeemed", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Voucher redeemed successfully",
                                      "data": {
                                        "redemptionId": "e3d9a1b2-1234-5678-2345-678901234567",
                                        "voucherId": "c1b7e9f0-9012-3456-0123-456789012345",
                                        "status": "REDEEMED",
                                        "usesRemaining": 0,
                                        "valueType": "AMOUNT",
                                        "value": 5.0000,
                                        "currency": "USD",
                                        "value": 5.0000,
                                        "valueType": "AMOUNT",
                                        "redeemedAt": "2026-05-04T14:00:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error",
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
                    responseCode = "404",
                    description = "Voucher code not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Voucher not found",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Anti-fraud rejection (signature mismatch, expired, wrong merchant, blocked, velocity)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Velocity blocked", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "VELOCITY_BLOCKED",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('CUSTOMER','SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.RedemptionResponse>> redeem(@Valid @RequestBody Dtos.RedeemVoucherRequest req) {
        Dtos.RedemptionResponse data = voucherService.redeem(tenantContext.requireTenantId(),
                CallerDetails.resolveMerchantId(req.merchantId()), req);
        return ResponseEntity.ok(ApiResult.ok("Voucher redeemed successfully", data));
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "Revoke an issued voucher",
            description = "Marks the voucher REVOKED so it can no longer be redeemed. Use for fraud, " +
                          "support refunds, or when a customer reports their code stolen. Already-redeemed " +
                          "vouchers cannot be revoked.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Voucher revoked",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Revoked", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Voucher revoked successfully",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Voucher not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Voucher not found",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Voucher already redeemed and cannot be revoked",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Already redeemed", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "ALREADY_REDEEMED",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Void>> revoke(@PathVariable UUID id) {
        voucherService.revoke(tenantContext.requireTenantId(), id);
        return ResponseEntity.ok(ApiResult.ok("Voucher revoked successfully", null));
    }

    @PostMapping("/codes/{code}/viewed")
    @Operation(summary = "Mark a voucher as viewed by the customer",
            description = "Read receipt — call this when the customer's app displays the voucher. " +
                          "Used by analytics to measure delivery-to-view conversion. No tenant header required " +
                          "since the code itself identifies the tenant.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "View recorded",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "View recorded", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Voucher view recorded",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Voucher code not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Voucher not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('CUSTOMER','SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Void>> markViewed(@PathVariable String code) {
        voucherService.markViewed(code);
        return ResponseEntity.ok(ApiResult.ok("Voucher view recorded", null));
    }

    @GetMapping("/users/by-phone/{phoneNumber}/active")
    @Operation(summary = "List a phone's active vouchers in the caller's tenant",
            description = "Returns every voucher in an active state (ISSUED, DELIVERED, VIEWED, " +
                          "PARTIALLY_USED) attached to the given phone's LoyaltyUser **within the tenant on " +
                          "the request** (X-Tenant-Id required). Powers the SuperApp \"my vouchers\" wallet " +
                          "view. Results are strictly tenant-scoped — a phone that also holds vouchers under " +
                          "another tenant will never surface them here. " +
                          "CUSTOMER callers can only request their own phone (JWT phoneNumber claim must " +
                          "match the path); MERCHANT_ADMIN / SHOP_ADMIN / SUPER_ADMIN can look up any phone " +
                          "for support, but only ever see their own tenant's vouchers.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Active vouchers returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Active vouchers", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Active vouchers retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "c1b7e9f0-9012-3456-0123-456789012345",
                                            "code": "VCH-AB12CD34",
                                            "status": "ISSUED",
                                            "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                            "assignedUserId": "d2c8f0a1-0123-4567-1234-567890123456",
                                            "assigneePhone": "+263771234567",
                                            "usesRemaining": 1,
                                            "valueType": "AMOUNT",
                                            "value": 5.0000,
                                            "currency": "USD",
                                            "issuedAt": "2026-05-04T10:30:00Z",
                                            "expiresAt": "2026-06-03T10:30:00Z"
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
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Missing X-Tenant-Id / X-Tenant-Code header",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "MISSING_TENANT",
                                      "message": "X-Tenant-Id or X-Tenant-Code header is required",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "CUSTOMER tried to read another customer's vouchers, or the caller is not a member of the tenant",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "NOT_PHONE_OWNER",
                                      "message": "you can only view vouchers for your own phone",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('CUSTOMER','SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<Dtos.VoucherResponse>>> activeForPhone(@PathVariable String phoneNumber,
                                                                                       @ParameterObject Pageable pageable) {
        // Gate 1 — tenant scope. X-Tenant-Id is required and TenantContext
        // enforces the caller's membership of it, exactly like GET
        // /users/{id}/transactions. This is what stops a cashier/admin in one
        // tenant from enumerating a customer's vouchers in another tenant: the
        // lookup below is scoped to this tenant only.
        UUID tenantId = tenantContext.requireTenantId();
        // Gate 2 — identity. CUSTOMER may only ask for their own phone (matches
        // the wallet-owner pattern in /users/{id}/transactions and
        // TransferService). Admin roles bypass this owner check for support /
        // ops, but stay bounded to their tenant by the scoped query above.
        requireCallerOwnsPhoneOrIsAdmin(phoneNumber);
        PageResponse<Dtos.VoucherResponse> data = PageResponse.from(
                voucherService.activeForPhone(tenantId, phoneNumber, pageable));
        return ResponseEntity.ok(ApiResult.ok("Active vouchers retrieved successfully", data));
    }

    /**
     * Authz gate for phone-keyed reads. Mirrors UserService.requireCallerOwnsOrIsAdmin
     * but works directly off a phone string (the phone-keyed wallet endpoints don't
     * have a LoyaltyUser handy at the call site).
     */
    private void requireCallerOwnsPhoneOrIsAdmin(String phoneNumber) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null) {
            for (var ga : auth.getAuthorities()) {
                String role = ga.getAuthority();
                if ("ROLE_SUPER_ADMIN".equals(role)
                        || "ROLE_MERCHANT_ADMIN".equals(role)
                        || "ROLE_SHOP_ADMIN".equals(role)
                        || "ROLE_SHOP_USER".equals(role)) {
                    return;
                }
            }
        }
        String callerPhone = com.innbucks.loyaltyservice.security.CallerDetails.currentPhoneNumber();
        if (callerPhone == null || !callerPhone.equals(phoneNumber)) {
            throw com.innbucks.loyaltyservice.exception.LoyaltyException.forbidden(
                    "NOT_PHONE_OWNER", "you can only view vouchers for your own phone");
        }
    }

    @GetMapping
    @Operation(summary = "Find vouchers by status",
            description = "Operator/merchant query: list every voucher in the given status across the tenant. " +
                          "Common statuses: ISSUED, PARTIALLY_USED, REDEEMED, EXPIRED, REVOKED.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Vouchers returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "By status", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Vouchers retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "c1b7e9f0-9012-3456-0123-456789012345",
                                            "code": "VCH-AB12CD34",
                                            "status": "ISSUED",
                                            "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                            "assignedUserId": "d2c8f0a1-0123-4567-1234-567890123456",
                                            "assigneePhone": "+254700000000",
                                            "usesRemaining": 1,
                                            "valueType": "AMOUNT",
                                            "value": 5.0000,
                                            "currency": "USD",
                                            "issuedAt": "2026-05-04T10:30:00Z",
                                            "expiresAt": "2026-06-03T10:30:00Z"
                                          },
                                          {
                                            "id": "f4eab2c3-2345-6789-3456-789012345678",
                                            "code": "VCH-IJ90KL12",
                                            "status": "ISSUED",
                                            "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                            "assignedUserId": null,
                                            "assigneePhone": "+254711111111",
                                            "usesRemaining": 1,
                                            "valueType": "AMOUNT",
                                            "value": 5.0000,
                                            "currency": "USD",
                                            "issuedAt": "2026-05-03T14:00:00Z",
                                            "expiresAt": "2026-06-02T14:00:00Z"
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
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Unknown status value",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Bad status", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Unknown voucher status: FOO",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<Dtos.VoucherResponse>>> findByStatus(@RequestParam("status") Voucher.Status status,
                                                                                      @ParameterObject Pageable pageable) {
        PageResponse<Dtos.VoucherResponse> data = PageResponse.from(
                voucherService.findByStatus(tenantContext.requireTenantId(), status, pageable));
        return ResponseEntity.ok(ApiResult.ok("Vouchers retrieved successfully", data));
    }
}
