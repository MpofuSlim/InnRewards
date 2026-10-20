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
    private final com.innbucks.loyaltyservice.service.EligibilityDeferral eligibilityDeferral;
    private final com.innbucks.loyaltyservice.service.TillBalanceService tillBalance;

    public ShopController(ShopService shops, TenantContext tenantContext, ShopCheckoutService shopCheckout,
                          com.innbucks.loyaltyservice.integration.GuestCheckoutNotifier guestCheckoutNotifier,
                          com.innbucks.loyaltyservice.service.EligibilityDeferral eligibilityDeferral,
                          com.innbucks.loyaltyservice.service.TillBalanceService tillBalance) {
        this.eligibilityDeferral = eligibilityDeferral;
        this.tillBalance = tillBalance;
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "The caller does not administer this merchant (NOT_MERCHANT_OWNER): a SHOP_ADMIN may add shops only to the merchant on its token, a MERCHANT_ADMIN only to its own organization's merchants. SUPER_ADMIN / TENANT_ADMIN / PLATFORM_ADMIN may use any merchant in the tenant.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Not your merchant", value = """
                                            {
                                              "code": "NOT_MERCHANT_OWNER",
                                              "message": "You can only act on merchants you administer.",
                                              "data": null
                                            }
                                            """)
                            })),
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "The caller does not administer this merchant (NOT_MERCHANT_OWNER): a SHOP_ADMIN may add shops only to the merchant on its token, a MERCHANT_ADMIN only to its own organization's merchants. SUPER_ADMIN / TENANT_ADMIN / PLATFORM_ADMIN may use any merchant in the tenant.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Not your merchant", value = """
                                            {
                                              "code": "NOT_MERCHANT_OWNER",
                                              "message": "You can only act on merchants you administer.",
                                              "data": null
                                            }
                                            """)
                            })),
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
            description = "Returns the shops the CALLER may see in the tenant. A token that names a " +
                          "shop (SHOP_USER / SHOP_ADMIN — the `shopId` claim) gets exactly that one shop, " +
                          "so a till can read its own outlet's name and needs no picker. A token that names " +
                          "only a merchant (`merchantId` claim) gets that merchant's shops. MERCHANT_ADMIN, " +
                          "SUPER_ADMIN and tenant roles see every shop in the tenant. Pass `merchantId` to " +
                          "filter to one merchant's outlets; a filter outside the caller's scope returns an " +
                          "empty page, not an error.")
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
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SHOP_USER','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
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
                    description = "The caller's token names a different shop (NOT_SHOP_MEMBER) or a different "
                            + "merchant (NOT_MERCHANT_OWNER). Shop staff read only their own shop.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Another outlet", value = """
                                            {
                                              "code": "NOT_SHOP_MEMBER",
                                              "message": "You can only access shops you are assigned to.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Another merchant's shop", value = """
                                            {
                                              "code": "NOT_MERCHANT_OWNER",
                                              "message": "You can only act on merchants you administer.",
                                              "data": null
                                            }
                                            """)
                            })),
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
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SHOP_USER','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ShopResponse>> get(@PathVariable UUID id) {
        Dtos.ShopResponse data = shops.getForCaller(tenantContext.requireTenantId(), id);
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
                    description = "The caller may not change this shop: a SHOP_ADMIN may change only the shop on its token (NOT_SHOP_MEMBER), a MERCHANT_ADMIN only its own organization's merchants' shops (NOT_MERCHANT_OWNER). SUPER_ADMIN / TENANT_ADMIN / PLATFORM_ADMIN may change any shop in the tenant.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Not your shop", value = """
                                            {
                                              "code": "NOT_SHOP_MEMBER",
                                              "message": "You can only access shops you are assigned to.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Not your merchant", value = """
                                            {
                                              "code": "NOT_MERCHANT_OWNER",
                                              "message": "You can only act on merchants you administer.",
                                              "data": null
                                            }
                                            """)
                            })),
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
                    description = "The caller may not change this shop: a SHOP_ADMIN may change only the shop on its token (NOT_SHOP_MEMBER), a MERCHANT_ADMIN only its own organization's merchants' shops (NOT_MERCHANT_OWNER). SUPER_ADMIN / TENANT_ADMIN / PLATFORM_ADMIN may change any shop in the tenant.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Not your shop", value = """
                                            {
                                              "code": "NOT_SHOP_MEMBER",
                                              "message": "You can only access shops you are assigned to.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Not your merchant", value = """
                                            {
                                              "code": "NOT_MERCHANT_OWNER",
                                              "message": "You can only act on merchants you administer.",
                                              "data": null
                                            }
                                            """)
                            })),
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
                    description = "The caller may not change this shop: a SHOP_ADMIN may change only the shop on its token (NOT_SHOP_MEMBER), a MERCHANT_ADMIN only its own organization's merchants' shops (NOT_MERCHANT_OWNER). SUPER_ADMIN / TENANT_ADMIN / PLATFORM_ADMIN may change any shop in the tenant.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Not your shop", value = """
                                            {
                                              "code": "NOT_SHOP_MEMBER",
                                              "message": "You can only access shops you are assigned to.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Not your merchant", value = """
                                            {
                                              "code": "NOT_MERCHANT_OWNER",
                                              "message": "You can only act on merchants you administer.",
                                              "data": null
                                            }
                                            """)
                            })),
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
            description = "Convenience endpoint for nested navigation. A token that names a shop sees only " +
                          "that shop here, and a token naming a different merchant is refused " +
                          "(NOT_MERCHANT_OWNER). Otherwise returns every shop belonging " +
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
                    description = "Caller's role is not permitted to list shops, or the caller's token names "
                            + "a different merchant",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Another merchant", value = """
                                    {
                                      "code": "NOT_MERCHANT_OWNER",
                                      "message": "You can only act on merchants you administer.",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No merchant with that id in this tenant")
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<List<Dtos.ShopResponse>>> listForMerchant(@PathVariable UUID merchantId) {
        List<Dtos.ShopResponse> data = shops.listForMerchant(tenantContext.requireTenantId(), merchantId);
        return ResponseEntity.ok(ApiResult.ok("Shops retrieved successfully", data));
    }

    /**
     * The shop a till call acts at, loaded in the caller's tenant and pinned to
     * the caller: shared by the checkout and the balance lookup so the two can
     * never disagree about which tills may use which shop.
     */
    private Dtos.ShopResponse requireTillShop(UUID tenantId, UUID shopId) {
        // A04/A01: the shop is loaded scoped to the caller's tenant, so a member
        // of one tenant can never act through another tenant's shop.
        Dtos.ShopResponse shop = shops.get(tenantId, shopId);
        // The merchant is taken from the SHOP itself (a shop belongs to exactly one
        // merchant). Ownership guard: a merchant-scoped caller (SHOP_ADMIN/SHOP_USER
        // carry merchantId in the JWT) may only act through its OWN shops.
        // MERCHANT_ADMIN/SUPER_ADMIN are tenant-scoped (currentMerchantId() is null)
        // and already bounded by the tenant-scoped shop load above.
        UUID callerMerchantId = CallerDetails.currentMerchantId();
        if (callerMerchantId != null && !callerMerchantId.equals(shop.merchantId())) {
            throw LoyaltyException.forbidden("SHOP_NOT_OWNED", "shop does not belong to your merchant");
        }
        // And to its own OUTLET: a caller whose token names a shop (SHOP_USER,
        // SHOP_ADMIN) acts only through that shop, the same pin
        // MerchantAuthz.requireCallerAccessesShop applies to the shop report.
        // Otherwise a cashier at one branch rings sales up under a sibling
        // branch, where nobody reconciles them against that till.
        UUID callerShopId = CallerDetails.currentShopId();
        if (callerShopId != null && !callerShopId.equals(shopId)) {
            throw LoyaltyException.forbidden("NOT_SHOP_MEMBER", "You can only access shops you are assigned to.");
        }
        return shop;
    }

    @PostMapping("/{shopId}/balance-lookup")
    @Operation(summary = "Till balance lookup: what the customer can pay with points here",
            description = "Call it after the cashier enters the phone and before the checkout. Returns the " +
                          "customer's spendable points, what they take off a bill at this shop (`pointsValue`, " +
                          "in the merchant's currency) and whether they can be spent here (`canSpendHere`). " +
                          "Send `pointsValue` as the checkout's `pointsValue` to use them all.\n\n" +
                          "Read-only: it never creates an account or registers a phone. A phone with no account " +
                          "is `points: 0`, `accountStatus: NONE`, never a 404. The phone is in the body so it " +
                          "stays out of URL logs. Same shop rules as the checkout; requires X-Tenant-Id.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Balance retrieved",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Can spend", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Balance retrieved",
                                              "data": {
                                                "phoneMasked": "****4567",
                                                "points": 970,
                                                "pointsValue": 9.70,
                                                "currency": "USD",
                                                "pointsPerUnit": 100.0000,
                                                "accountStatus": "ACTIVE",
                                                "canSpendHere": true
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "No account at this brand", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Balance retrieved",
                                              "data": {
                                                "phoneMasked": "****4567",
                                                "points": 0,
                                                "pointsValue": 0.00,
                                                "currency": "USD",
                                                "pointsPerUnit": 100.0000,
                                                "accountStatus": "NONE",
                                                "canSpendHere": false
                                              }
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Bad phone, or the merchant's currency cannot be priced",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Missing phone", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Validation failed",
                                              "data": { "phoneNumber": "must not be blank" }
                                            }
                                            """),
                                    @ExampleObject(name = "Not a phone number", value = """
                                            {
                                              "code": "BAD_PHONE",
                                              "message": "Invalid phone number: 12345",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Not this till's shop",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Another outlet", value = """
                                            {
                                              "code": "NOT_SHOP_MEMBER",
                                              "message": "You can only access shops you are assigned to.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Another merchant's shop", value = """
                                            {
                                              "code": "SHOP_NOT_OWNED",
                                              "message": "shop does not belong to your merchant",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Unknown shop",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Unknown shop", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "shop not found",
                                      "data": null
                                    }
                                    """)))
    })
    @PreAuthorize("hasAnyRole('SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TillBalanceResponse>> balanceLookup(
            @PathVariable UUID shopId,
            @Valid @RequestBody Dtos.TillBalanceRequest req) {
        UUID tenantId = tenantContext.requireTenantId();
        Dtos.ShopResponse shop = requireTillShop(tenantId, shopId);
        Dtos.TillBalanceResponse data = tillBalance.lookup(tenantId, shop.merchantId(), req.phoneNumber());
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiResult.ok("Balance retrieved", data));
    }

    @PostMapping("/{shopId}/guest-checkout")
    @Operation(summary = "Till checkout — pay in cash, points, or both",
            description = "Merchant-authenticated (requires a shop-operator bearer token + X-Tenant-Id). " +
                          "The customer is identified by phone number and splits the bill the way they ask: " +
                          "`cashAmount` is paid in cash (or by card, `transactionType: CARD_PAYMENT`) and " +
                          "earns points; `pointsValue` is paid with points. Either may be omitted. Both legs " +
                          "commit together: if either is refused, nothing is written.\n\n" +
                          "**Cash only:** no account needed — a PENDING wallet is created for the phone and " +
                          "receives the points; they become spendable once the phone registers.\n\n" +
                          "**Points part:** burned FIRST, at the platform redemption rate, so points earned on " +
                          "this sale's cash can never pay for it. It runs the checks of `POST /loyalty/redeem`: " +
                          "the customer must already have an account here (404 otherwise — nothing is " +
                          "created), be registered (403 USER_PENDING) and hold enough points (400 " +
                          "INSUFFICIENT_FUNDS).\n\n" +
                          "**Retries:** send the till's receipt `reference`. Resending the same reference and " +
                          "phone returns the original checkout with `replayed: true` and moves nothing; the " +
                          "same reference for another phone is 409 DUPLICATE_REFERENCE. Without a reference " +
                          "the server generates one, and a resend is a second sale.\n\n" +
                          "The merchant is derived from the shop, so it is NOT in the request body. The caller " +
                          "must own the shop: SHOP_ADMIN/SHOP_USER must carry the shop's merchant in their " +
                          "JWT, and when their JWT names a shop it must be THIS shop; MERCHANT_ADMIN/" +
                          "SUPER_ADMIN are scoped by tenant membership. The phone is staff-typed, so a staff " +
                          "member cannot earn to their own phone (SELF_EARN) or a colleague's (STAFF_RECIPIENT).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Checkout recorded (or replayed)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Cash and points", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Guest checkout completed successfully",
                                      "data": {
                                        "shopId": "11111111-aaaa-bbbb-cccc-222222222222",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "loyaltyUserId": "99999999-8888-7777-6666-555555555555",
                                        "cashAmount": 12.00,
                                        "pointsEarned": 24,
                                        "walletBalanceAfter": 1024,
                                        "purchaseTransactionId": "7a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9",
                                        "pointsRedeemed": 500,
                                        "pointsValue": 5.00,
                                        "currency": "USD",
                                        "redemptionTransactionId": "8b2c3d4e-5f60-7182-93a4-b5c6d7e8f9a0",
                                        "replayed": false
                                      }
                                    }
                                    """),
                                    @ExampleObject(name = "Cash only", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Guest checkout completed successfully",
                                      "data": {
                                        "shopId": "11111111-aaaa-bbbb-cccc-222222222222",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "loyaltyUserId": "99999999-8888-7777-6666-555555555555",
                                        "cashAmount": 10.00,
                                        "pointsEarned": 20,
                                        "walletBalanceAfter": 20,
                                        "purchaseTransactionId": "7a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9",
                                        "pointsRedeemed": 0,
                                        "pointsValue": 0,
                                        "currency": "USD",
                                        "redemptionTransactionId": null,
                                        "replayed": false
                                      }
                                    }
                                    """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Invalid request, nothing to pay, an unknown type, or not enough points",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Validation error", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Validation failed",
                                              "data": { "pointsValue": "must be greater than 0" }
                                            }
                                            """),
                                    @ExampleObject(name = "Nothing to pay", value = """
                                            {
                                              "code": "AMOUNT_REQUIRED",
                                              "message": "at least one of cashAmount or pointsValue must be positive",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Not enough points", value = """
                                            {
                                              "code": "INSUFFICIENT_FUNDS",
                                              "message": "You don't have enough loyalty points for this.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Unsupported type", value = """
                                            {
                                              "code": "BAD_TRANSACTION_TYPE",
                                              "message": "transactionType must be PURCHASE or CARD_PAYMENT.",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller's role can't check out, the shop isn't under the caller's merchant "
                            + "(SHOP_NOT_OWNED) or isn't the caller's own outlet (NOT_SHOP_MEMBER), the phone "
                            + "is the caller's own (SELF_EARN) or a staff member's of this merchant "
                            + "(STAFF_RECIPIENT), or — for a points part — the customer can't spend yet "
                            + "(USER_PENDING) or is blocked (USER_BLOCKED).",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Not your shop", value = """
                                            {
                                              "code": "SHOP_NOT_OWNED",
                                              "message": "shop does not belong to your merchant",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Another outlet", value = """
                                            {
                                              "code": "NOT_SHOP_MEMBER",
                                              "message": "You can only access shops you are assigned to.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Cashier's own phone", value = """
                                            {
                                              "code": "SELF_EARN",
                                              "message": "You can't award points to your own account.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "A colleague's phone", value = """
                                            {
                                              "code": "STAFF_RECIPIENT",
                                              "message": "Points can't be awarded to a staff account of this merchant.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Customer not registered", value = """
                                            {
                                              "code": "USER_PENDING",
                                              "message": "Your rewards account is still being set up, so these points can't be spent yet. You'll keep earning in the meantime.",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No shop with that id in this tenant, or — for a points part — no customer "
                            + "account for the phone here",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "No account for the phone", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "user not found",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "The reference was already used for another customer or transaction",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Reference already used", value = """
                                    {
                                      "code": "DUPLICATE_REFERENCE",
                                      "message": "transaction with this merchant reference already exists",
                                      "data": null
                                    }
                                    """)))
    })
    @PreAuthorize("hasAnyRole('SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.GuestShopCheckoutResponse>> guestCheckout(
            @PathVariable UUID shopId,
            @Valid @RequestBody Dtos.GuestShopCheckoutRequest req) {
        // A04/A01: the caller is an authenticated shop operator (X-Tenant-Id
        // required). Load the shop scoped to the caller's tenant so a member of
        // one tenant can never check out through another tenant's shop.
        UUID tenantId = tenantContext.requireTenantId();
        Dtos.ShopResponse shop = requireTillShop(tenantId, shopId);
        // The till's receipt reference is the idempotency key for both legs. A
        // POS that sends none gets a server-generated one (the old behaviour),
        // so its resend is a new sale. Mirrors /payments/shop-checkout's
        // "SHOP-" + UUID convention.
        String reference = req.reference() == null || req.reference().isBlank()
                ? "SHOP-" + UUID.randomUUID()
                : req.reference().trim();
        // Load the STAFF_RECIPIENT snapshot before the checkout's transaction
        // opens, so the guard inside it never waits on user-service. Never refuses.
        shopCheckout.prewarmStaffRecipientGuard(tenantId, shop.merchantId());
        // TYPED_PHONE inside tillCheckout: the cashier keyed this phone in, so
        // the earn-integrity guards apply. The points part is a spend, so the
        // V44 eligibility check runs outside the checkout's transaction.
        ShopCheckoutService.TillResult t = eligibilityDeferral.run(() -> shopCheckout.tillCheckout(
                tenantId, shopId, req.phoneNumber(), req.cashAmount(), req.pointsValue(), req.currency(),
                req.transactionType(), reference));
        ShopCheckoutService.Result r = t.result();
        Dtos.GuestShopCheckoutResponse data = new Dtos.GuestShopCheckoutResponse(
                r.shopId(), r.merchantId(), r.loyaltyUserId(),
                r.cashAmount(), r.pointsEarned(), r.walletBalanceAfter(), r.purchaseTransactionId(),
                r.pointsRedeemed(), t.pointsValue(), t.currency(), r.redemptionTransactionId(), t.replayed());
        // Best-effort congratulations to the walk-in customer (SMS primary, WhatsApp
        // fallback) — not on a replay, which already sent it. @Async + self-contained
        // best-effort — never delays or fails the 201, but guard defensively so even
        // a bean-proxy hiccup can't break checkout.
        if (!t.replayed()) {
            try {
                guestCheckoutNotifier.notifyPointsEarned(
                        shop.name(), req.phoneNumber(), r.pointsEarned(), r.walletBalanceAfter());
            } catch (RuntimeException e) {
                log.warn("Failed to dispatch guest-checkout notification: {}", e.getMessage());
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Guest checkout completed successfully", data));
    }
}
