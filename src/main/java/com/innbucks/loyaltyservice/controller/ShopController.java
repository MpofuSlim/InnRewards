package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.ShopCheckoutService;
import com.innbucks.loyaltyservice.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/loyalty/shops")
@Tag(name = "Shops",
     description = "Physical outlets under a Merchant. e.g. \"Pizza Inn Avondale\" and " +
                   "\"Pizza Inn Westgate\" are two shops under the \"Pizza Inn\" merchant. " +
                   "Shops inherit their merchant's loyalty rules transparently — earn-rate " +
                   "evaluation falls through merchant-specific rules → global tenant rules " +
                   "automatically when a transaction is posted with the shop's merchantId. " +
                   "Requires X-Tenant-Id.")
public class ShopController {

    private final ShopService shops;
    private final TenantContext tenantContext;
    private final ShopCheckoutService shopCheckout;
    private final com.innbucks.loyaltyservice.integration.GuestCheckoutNotifier guestCheckoutNotifier;

    public ShopController(ShopService shops, TenantContext tenantContext, ShopCheckoutService shopCheckout,
                          com.innbucks.loyaltyservice.integration.GuestCheckoutNotifier guestCheckoutNotifier) {
        this.shops = shops;
        this.tenantContext = tenantContext;
        this.shopCheckout = shopCheckout;
        this.guestCheckoutNotifier = guestCheckoutNotifier;
    }

    @PostMapping
    @Operation(summary = "Onboard a shop under a merchant",
            description = "Creates a new shop outlet under the given merchant. `merchantId` is supplied " +
                          "in the request body and must reference a merchant that exists in the current " +
                          "tenant.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Shop created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Shop created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Shop created successfully",
                                      "data": {
                                        "id": "11111111-aaaa-bbbb-cccc-222222222222",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "name": "Pizza Inn Avondale",
                                        "address": "123 King George Rd, Avondale, Harare",
                                        "status": "ACTIVE",
                                        "createdAt": "2026-05-11T10:15:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (missing merchantId or name)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "merchantId: must not be null",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Merchant not found in this tenant"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "A shop with that name already exists under this merchant (case-insensitive)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Name taken", value = """
                                    {
                                      "code": "SHOP_NAME_TAKEN",
                                      "message": "A shop with that name already exists for this merchant.",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ShopResponse>> create(@Valid @RequestBody Dtos.ShopRequest req) {
        Dtos.ShopResponse data = shops.create(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Shop created successfully", data));
    }

    @PostMapping(value = "/bulk-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Bulk-upload shops via CSV",
            description = "Imports a CSV of outlets under one merchant. Useful when a brand onboards " +
                          "tens or hundreds of shops at once (e.g. launching a chain in a new country). " +
                          "Each row runs in its own DB transaction — a validation failure on row 7 " +
                          "does not block rows 1-6. The response lists every failed row with the " +
                          "reason so the operator can correct the source spreadsheet and re-upload " +
                          "only the affected rows.\n\n" +
                          "**Duplicate names are skipped, not inserted.** A row whose name (case-insensitive) " +
                          "already exists for the merchant, or duplicates an earlier row in the same file, " +
                          "is reported as a failure with reason `duplicate shop name` — the first occurrence " +
                          "wins and no duplicate shop is created.\n\n" +
                          "**CSV format:** required header row with a `name` column (case-insensitive); " +
                          "an optional `address` column is recognised if present. Extra columns are " +
                          "ignored. Quoted fields support embedded commas (e.g. " +
                          "`\"123 King George Rd, Avondale, Harare\"`). Max " +
                          "5000 data rows per upload.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Upload processed. Some or all rows may have failed; check the response body.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Mixed success", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Bulk shop upload processed",
                                      "data": {
                                        "processed": 4,
                                        "created": 2,
                                        "failed": 2,
                                        "failures": [
                                          {
                                            "row": 3,
                                            "name": null,
                                            "error": "name is required"
                                          },
                                          {
                                            "row": 4,
                                            "name": "Pizza Inn Avondale",
                                            "error": "duplicate shop name"
                                          }
                                        ]
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Upload itself is malformed — empty file, missing `name` header, or row count over the limit.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Missing header", value = """
                                    {
                                      "code": "CSV_MISSING_HEADER",
                                      "message": "header row must contain a 'name' column (case-insensitive); got: foo,bar",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "merchantId does not resolve to a merchant in this tenant.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Merchant not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "merchant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.BulkShopUploadResult>> bulkUpload(
            @Parameter(description = "Merchant that every shop in the upload belongs to.")
            @RequestParam("merchantId") java.util.UUID merchantId,
            @Parameter(description = "CSV file with header row + name/address columns.")
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        if (file == null || file.isEmpty()) {
            throw com.innbucks.loyaltyservice.exception.LoyaltyException.badRequest(
                    "CSV_EMPTY", "uploaded file is empty");
        }
        Dtos.BulkShopUploadResult data = shops.bulkUploadFromCsv(
                tenantContext.requireTenantId(), merchantId, file.getInputStream());
        return ResponseEntity.ok(ApiResult.ok("Bulk shop upload processed", data));
    }

    @GetMapping
    @Operation(summary = "List shops for the current tenant",
            description = "Returns every shop in the tenant. Pass `merchantId` to filter to a single " +
                          "merchant's outlets.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Shops returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated shops", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shops retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "11111111-aaaa-bbbb-cccc-222222222222",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "name": "Pizza Inn Avondale",
                                                "address": "123 King George Rd, Avondale, Harare",
                                            "status": "ACTIVE",
                                            "createdAt": "2026-05-11T10:15:00Z"
                                          },
                                          {
                                            "id": "22222222-aaaa-bbbb-cccc-333333333333",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "name": "Pizza Inn Westgate",
                                            "address": "Westgate Shopping Mall, Harare",
                                            "status": "ACTIVE",
                                            "createdAt": "2026-05-11T10:20:00Z"
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
    public ResponseEntity<ApiResult<PageResponse<Dtos.ShopResponse>>> list(
            @Parameter(description = "Optional merchant filter — return only shops under this merchant.")
            @RequestParam(required = false) UUID merchantId,
            @ParameterObject Pageable pageable) {
        PageResponse<Dtos.ShopResponse> data = PageResponse.from(
                shops.list(tenantContext.requireTenantId(), merchantId, pageable));
        return ResponseEntity.ok(ApiResult.ok("Shops retrieved successfully", data));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a shop by id")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Shop returned",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Shop", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shop retrieved successfully",
                                      "data": {
                                        "id": "11111111-aaaa-bbbb-cccc-222222222222",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "name": "Pizza Inn Avondale",
                                        "address": "123 King George Rd, Avondale, Harare",
                                        "status": "ACTIVE",
                                        "createdAt": "2026-05-11T10:15:00Z"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller's role is not permitted to read shops"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No shop with that id in this tenant",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "shop not found",
                                      "data": null
                                    }
                                    """)))
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ShopResponse>> get(@PathVariable UUID id) {
        Dtos.ShopResponse data = shops.get(tenantContext.requireTenantId(), id);
        return ResponseEntity.ok(ApiResult.ok("Shop retrieved successfully", data));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a shop",
            description = "Updates display name, outlet code, or address.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Shop updated",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Updated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shop updated successfully",
                                      "data": {
                                        "id": "11111111-aaaa-bbbb-cccc-222222222222",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "name": "Pizza Inn Avondale (renamed)",
                                        "address": "456 Samora Machel Ave, Harare",
                                        "status": "ACTIVE",
                                        "createdAt": "2026-05-11T10:15:00Z"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation failure",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Missing field", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "name: must not be blank",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller's role is not permitted to update shops"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No shop with that id in this tenant")
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ShopResponse>> update(@PathVariable UUID id,
                                                               @Valid @RequestBody Dtos.ShopRequest req) {
        Dtos.ShopResponse data = shops.update(tenantContext.requireTenantId(), id, req);
        return ResponseEntity.ok(ApiResult.ok("Shop updated successfully", data));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate a shop",
            description = "Sets status to ACTIVE. Idempotent.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Shop activated (or already active — idempotent)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Activated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shop activated successfully",
                                      "data": {
                                        "id": "11111111-aaaa-bbbb-cccc-222222222222",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "name": "Pizza Inn Avondale",
                                        "address": "123 King George Rd, Avondale, Harare",
                                        "status": "ACTIVE",
                                        "createdAt": "2026-05-11T10:15:00Z"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller's role is not permitted to activate shops"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No shop with that id in this tenant")
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ShopResponse>> activate(@PathVariable UUID id) {
        Dtos.ShopResponse data = shops.setActive(tenantContext.requireTenantId(), id, true);
        return ResponseEntity.ok(ApiResult.ok("Shop activated successfully", data));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a shop",
            description = "Sets status to INACTIVE. The parent merchant and any issued vouchers are " +
                          "unaffected; only the shop outlet is taken offline.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Shop deactivated (or already inactive — idempotent)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Deactivated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shop deactivated successfully",
                                      "data": {
                                        "id": "11111111-aaaa-bbbb-cccc-222222222222",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "name": "Pizza Inn Avondale",
                                        "address": "123 King George Rd, Avondale, Harare",
                                        "status": "INACTIVE",
                                        "createdAt": "2026-05-11T10:15:00Z"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller's role is not permitted to deactivate shops"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No shop with that id in this tenant")
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ShopResponse>> deactivate(@PathVariable UUID id) {
        Dtos.ShopResponse data = shops.setActive(tenantContext.requireTenantId(), id, false);
        return ResponseEntity.ok(ApiResult.ok("Shop deactivated successfully", data));
    }

    @GetMapping("/by-merchant/{merchantId}")
    @Operation(summary = "List shops under a merchant",
            description = "Convenience endpoint for nested navigation — returns every shop belonging " +
                          "to the given merchant in the current tenant.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Shops returned (empty array if the merchant has none)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Two shops", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shops retrieved successfully",
                                      "data": [
                                        {
                                          "id": "11111111-aaaa-bbbb-cccc-222222222222",
                                          "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "name": "Pizza Inn Avondale",
                                            "address": "123 King George Rd, Avondale, Harare",
                                          "status": "ACTIVE",
                                          "createdAt": "2026-05-11T10:15:00Z"
                                        },
                                        {
                                          "id": "22222222-bbbb-cccc-dddd-333333333333",
                                          "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "name": "Pizza Inn Borrowdale",
                                          "address": "12 Sam Levy's Village, Borrowdale, Harare",
                                          "status": "ACTIVE",
                                          "createdAt": "2026-05-11T10:20:00Z"
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller's role is not permitted to list shops"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No merchant with that id in this tenant")
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<List<Dtos.ShopResponse>>> listForMerchant(@PathVariable UUID merchantId) {
        List<Dtos.ShopResponse> data = shops.listForMerchant(tenantContext.requireTenantId(), merchantId);
        return ResponseEntity.ok(ApiResult.ok("Shops retrieved successfully", data));
    }

    @PostMapping("/{shopId}/guest-checkout")
    @Operation(summary = "Guest checkout — earn points for an unregistered customer",
            description = "Merchant-authenticated (requires a shop-operator bearer token + X-Tenant-Id). " +
                          "The shop earns loyalty points on a cash amount for a " +
                          "walk-in customer identified by phone number only — no account required. The " +
                          "customer RECEIVES points immediately (a PENDING wallet is auto-created and keyed " +
                          "to the phone) but cannot REDEEM until they register, at which point the accrued " +
                          "balance becomes spendable. Cash-only: no points are burned. The merchant is " +
                          "derived from the shop, so it is NOT in the request body. The caller must own " +
                          "the shop: SHOP_ADMIN/SHOP_USER must carry the shop's merchant in their JWT; " +
                          "MERCHANT_ADMIN/SUPER_ADMIN are scoped by tenant membership. Requires X-Tenant-Id.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Points earned for the guest",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Earned", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Guest checkout completed successfully",
                                      "data": {
                                        "shopId": "11111111-aaaa-bbbb-cccc-222222222222",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "loyaltyUserId": "99999999-8888-7777-6666-555555555555",
                                        "cashAmount": 10.00,
                                        "pointsEarned": 10.0000,
                                        "walletBalanceAfter": 10.0000,
                                        "purchaseTransactionId": "7a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation failure (blank phoneNumber / non-positive cashAmount)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "cashAmount: must be greater than 0",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller's role can't check out, or the shop isn't under the caller's merchant",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not your shop", value = """
                                    {
                                      "code": "SHOP_NOT_OWNED",
                                      "message": "shop does not belong to your merchant",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No shop with that id in this tenant")
    })
    @PreAuthorize("hasAnyRole('SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.GuestShopCheckoutResponse>> guestCheckout(
            @PathVariable UUID shopId,
            @Valid @RequestBody Dtos.GuestShopCheckoutRequest req) {
        // A04/A01: the caller is an authenticated shop operator (X-Tenant-Id
        // required). Load the shop scoped to the caller's tenant so a member of
        // one tenant can never earn through another tenant's shop.
        UUID tenantId = tenantContext.requireTenantId();
        Dtos.ShopResponse shop = shops.get(tenantId, shopId);
        // The merchant is taken from the SHOP itself (a shop belongs to exactly one
        // merchant). Ownership guard: a merchant-scoped caller (SHOP_ADMIN/SHOP_USER
        // carry merchantId in the JWT) may only earn through its OWN shops.
        // MERCHANT_ADMIN/SUPER_ADMIN are tenant-scoped (currentMerchantId() is null)
        // and already bounded by the tenant-scoped shop load above.
        UUID callerMerchantId = CallerDetails.currentMerchantId();
        if (callerMerchantId != null && !callerMerchantId.equals(shop.merchantId())) {
            throw LoyaltyException.forbidden("SHOP_NOT_OWNED", "shop does not belong to your merchant");
        }
        // Reference is server-owned (per-merchant idempotency on the loyalty PURCHASE
        // row); the POS never supplies one. Mirrors /payments/shop-checkout's
        // "SHOP-" + UUID convention.
        String reference = "SHOP-" + UUID.randomUUID();
        // Cash-only: pointsAmount = ZERO skips the burn/redemption leg, so a PENDING
        // (unregistered) customer earns without a spendable-balance check.
        ShopCheckoutService.Result r = shopCheckout.checkout(
                shopId, req.phoneNumber(), req.cashAmount(), BigDecimal.ZERO, reference);
        Dtos.GuestShopCheckoutResponse data = new Dtos.GuestShopCheckoutResponse(
                r.shopId(), r.merchantId(), r.loyaltyUserId(),
                r.cashAmount(), r.pointsEarned(), r.walletBalanceAfter(), r.purchaseTransactionId());
        // Best-effort congratulations to the walk-in customer (SMS primary, WhatsApp
        // fallback). @Async + self-contained best-effort — never delays or fails the
        // 201, but guard defensively so even a bean-proxy hiccup can't break checkout.
        try {
            guestCheckoutNotifier.notifyPointsEarned(
                    shop.name(), req.phoneNumber(), r.pointsEarned(), r.walletBalanceAfter());
        } catch (RuntimeException e) {
            log.warn("Failed to dispatch guest-checkout notification: {}", e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Guest checkout completed successfully", data));
    }
}
