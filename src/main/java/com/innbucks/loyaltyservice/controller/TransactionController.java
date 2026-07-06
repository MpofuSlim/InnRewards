package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.RedemptionService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.TransferService;
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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/loyalty")
@Tag(name = "Transactions",
     description = "Points lifecycle: earn, redeem, adjust, reverse, transfer. Every successful transaction " +
                   "writes an immutable row to `points_ledger` and stamps the rule_id/campaign_id used, " +
                   "so balances can always be reconstructed from the ledger. Requires X-Tenant-Id.")
public class TransactionController {

    private final TransactionService transactions;
    private final TransferService transferService;
    private final RedemptionService redemptionService;
    private final TenantContext tenantContext;
    private final com.innbucks.loyaltyservice.service.UserService users;

    public TransactionController(TransactionService transactions, TransferService transferService,
                                 RedemptionService redemptionService, TenantContext tenantContext,
                                 com.innbucks.loyaltyservice.service.UserService users) {
        this.transactions = transactions;
        this.transferService = transferService;
        this.redemptionService = redemptionService;
        this.tenantContext = tenantContext;
        this.users = users;
    }

    @PostMapping("/transactions")
    @Operation(summary = "Post an earn transaction",
            description = "Awards points for a customer purchase or QR pay. RulesEngine selects the most " +
                          "specific active rule + highest active campaign multiplier and writes one row to " +
                          "`points_ledger`. Idempotent on `(merchantId, reference)` — duplicate references " +
                          "return DUPLICATE_REFERENCE so POS retries can be safely repeated. Rejects with " +
                          "USER_BLOCKED if the loyalty user is blocked from the program.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Transaction posted",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Transaction posted", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Transaction posted successfully",
                                      "data": {
                                        "id": "11111111-2222-3333-4444-555555555555",
                                        "type": "PURCHASE",
                                        "amount": 100.00,
                                        "pointsDelta": 100.0000,
                                        "balanceAfter": 5100.0000,
                                        "ruleId": "d6e2f4a5-4567-8901-bcde-f01234567890",
                                        "campaignId": "f8a4b6c7-6789-0123-def0-123456789012",
                                        "reference": "POS-20260504-0001",
                                        "createdAt": "2026-05-04T11:00:00Z"
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
                                      "message": "userId: must not be null",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Business rule violation (DUPLICATE_REFERENCE, USER_BLOCKED, MERCHANT_INACTIVE)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Duplicate reference", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "DUPLICATE_REFERENCE",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TransactionResponse>> post(@Valid @RequestBody Dtos.TransactionRequest req) {
        Dtos.TransactionResponse data = transactions.post(tenantContext.requireTenantId(),
                CallerDetails.resolveMerchantId(req.merchantId()), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Transaction posted successfully", data));
    }

    @PostMapping("/transactions/{id}/reverse")
    @Operation(summary = "Reverse a transaction",
            description = "Issues a compensating ledger entry that negates the points originally awarded by " +
                          "transaction `{id}`. The original row is preserved (immutable ledger). Used for " +
                          "refunds and chargebacks. Optional JSON body: `{ \"reason\": \"...\" }`.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Transaction reversed",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Reversal posted", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Transaction reversed successfully",
                                      "data": {
                                        "id": "22222222-3333-4444-5555-666666666666",
                                        "type": "REFUND",
                                        "amount": 100.00,
                                        "pointsDelta": -100.0000,
                                        "balanceAfter": 5000.0000,
                                        "ruleId": null,
                                        "campaignId": null,
                                        "reference": "REVERSE:POS-20260504-0001",
                                        "createdAt": "2026-05-04T12:30:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Original transaction not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Transaction not found",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Already reversed or otherwise non-reversible",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Already reversed", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "ALREADY_REVERSED",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TransactionResponse>> reverse(@PathVariable UUID id,
                                            @Valid @RequestBody(required = false) Dtos.PointsReverseRequestDTO body) {
        String reason = body == null ? null : body.reason();
        Dtos.TransactionResponse data = transactions.reverse(tenantContext.requireTenantId(), id, reason);
        return ResponseEntity.ok(ApiResult.ok("Transaction reversed successfully", data));
    }

    @PostMapping("/transactions/adjust")
    @Operation(summary = "Manually adjust a user's points",
            description = "Operator escape hatch — credits or debits points outside the normal earn/redeem " +
                          "flow. Body: `{ userId, merchantId, points, reason }` where `points` may be " +
                          "positive (credit) or negative (debit). Always recorded in the ledger with " +
                          "type=ADJUSTMENT for audit.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Adjustment applied",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Adjustment applied", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Adjustment applied successfully",
                                      "data": {
                                        "id": "33333333-4444-5555-6666-777777777777",
                                        "type": "ADJUSTMENT",
                                        "amount": null,
                                        "pointsDelta": 250.0000,
                                        "balanceAfter": 5250.0000,
                                        "ruleId": null,
                                        "campaignId": null,
                                        "reference": "Goodwill credit",
                                        "createdAt": "2026-05-04T13:15:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Malformed body (bad UUID / non-numeric points)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Bad request", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Invalid UUID string: foo",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "User or merchant not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "User not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TransactionResponse>> adjust(@Valid @RequestBody Dtos.PointsAdjustRequestDTO body) {
        Dtos.TransactionResponse data = transactions.adjust(tenantContext.requireTenantId(),
                body.userId(), body.merchantId(), body.points(), body.reason());
        return ResponseEntity.ok(ApiResult.ok("Adjustment applied successfully", data));
    }

    @GetMapping("/users/{id}/transactions")
    @Operation(summary = "Get a user's recent transactions",
            description = "Returns the most recent transactions for the loyalty user `{id}` (the LoyaltyUser " +
                          "UUID, not the user-service userId). Powers the SuperApp activity feed.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Transactions returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated transactions", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Transactions retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "11111111-2222-3333-4444-555555555555",
                                            "type": "PURCHASE",
                                            "amount": 100.00,
                                            "pointsDelta": 100.0000,
                                            "balanceAfter": 5100.0000,
                                            "ruleId": "d6e2f4a5-4567-8901-bcde-f01234567890",
                                            "campaignId": null,
                                            "reference": "POS-20260504-0001",
                                            "createdAt": "2026-05-04T11:00:00Z"
                                          },
                                          {
                                            "id": "22222222-3333-4444-5555-666666666666",
                                            "type": "REDEMPTION",
                                            "amount": null,
                                            "pointsDelta": -500.0000,
                                            "balanceAfter": 4600.0000,
                                            "ruleId": null,
                                            "campaignId": null,
                                            "reference": "VOUCHER:VCH-AB12CD",
                                            "createdAt": "2026-05-04T12:00:00Z"
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
    @PreAuthorize("hasAnyRole('CUSTOMER','MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<Dtos.TransactionResponse>>> recent(@PathVariable UUID id,
                                                                                    @ParameterObject Pageable pageable) {
        // Gate 1 — tenant scope. Every other tenant-scoped endpoint requires
        // this header; this one used to be the exception and returned data
        // across tenants. Closes that gap.
        UUID tenantId = tenantContext.requireTenantId();
        // Gate 2 — cross-tenant guard. users.require throws CROSS_TENANT if
        // the target user belongs to a different tenant than the header. After
        // this line we know the LoyaltyUser is genuinely in this tenant.
        var target = users.require(tenantId, id);
        // Gate 3 — identity. A plain CUSTOMER can only read their own ledger.
        // Admin roles (SUPER_ADMIN, MERCHANT_ADMIN, SHOP_ADMIN) bypass — they're
        // explicitly allowed to inspect any user in their tenant for support/ops.
        users.requireCallerOwnsOrIsAdmin(target);
        PageResponse<Dtos.TransactionResponse> data = PageResponse.from(transactions.recentForUser(id, pageable));
        return ResponseEntity.ok(ApiResult.ok("Transactions retrieved successfully", data));
    }

    @GetMapping("/transactions/my-shop")
    @Operation(summary = "Get this shop's transactions",
            description = "Returns every loyalty transaction stamped with the caller's shopId, most " +
                          "recent first. The shopId comes from the authenticated JWT (SHOP_USER and " +
                          "SHOP_ADMIN tokens carry it); the FE never sends it. Used by the shop-staff " +
                          "dashboard so a cashier sees only their own outlet's earn / redemption / " +
                          "reversal feed, not the whole merchant chain's.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Transactions returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated transactions", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Transactions retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "11111111-2222-3333-4444-555555555555",
                                            "type": "PURCHASE",
                                            "amount": 100.00,
                                            "pointsDelta": 100.0000,
                                            "balanceAfter": null,
                                            "ruleId": "d6e2f4a5-4567-8901-bcde-f01234567890",
                                            "campaignId": null,
                                            "shopId": "c7d8e9f0-1234-5678-90ab-cdef12345678",
                                            "reference": "POS-20260504-0001",
                                            "createdAt": "2026-05-04T11:00:00Z"
                                          },
                                          {
                                            "id": "22222222-3333-4444-5555-666666666666",
                                            "type": "REDEMPTION",
                                            "amount": null,
                                            "pointsDelta": -500.0000,
                                            "balanceAfter": null,
                                            "ruleId": null,
                                            "campaignId": null,
                                            "shopId": "c7d8e9f0-1234-5678-90ab-cdef12345678",
                                            "reference": "VOUCHER:VCH-AB12CD",
                                            "createdAt": "2026-05-04T12:00:00Z"
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
                    description = "Caller's JWT has no shopId — token isn't shop-staff",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "No shop scope", value = """
                                    {
                                      "code": "SHOP_REQUIRED",
                                      "message": "caller's JWT has no shopId; endpoint is for SHOP_USER and SHOP_ADMIN only",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Missing or invalid bearer token",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Unauthenticated", value = """
                                    {
                                      "code": "INVALID_TOKEN",
                                      "message": "Token is invalid or expired",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('SHOP_USER','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<Dtos.TransactionResponse>>> myShop(
            @ParameterObject Pageable pageable) {
        UUID tenantId = tenantContext.requireTenantId();
        UUID shopId = CallerDetails.currentShopId();
        if (shopId == null) {
            throw com.innbucks.loyaltyservice.exception.LoyaltyException.badRequest(
                    "SHOP_REQUIRED",
                    "caller's JWT has no shopId; endpoint is for SHOP_USER and SHOP_ADMIN only");
        }
        PageResponse<Dtos.TransactionResponse> data = PageResponse.from(
                transactions.recentForShop(tenantId, shopId, pageable));
        return ResponseEntity.ok(ApiResult.ok("Transactions retrieved successfully", data));
    }

    @PostMapping("/transfer")
    @Operation(summary = "Transfer points between users (P2P)",
            description = "Moves points from `fromUserId` to `toUserId` atomically using canonical-order " +
                          "wallet locking to avoid deadlocks. Both must belong to the current tenant. " +
                          "Returns the sender's new balance.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Transfer completed",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Transfer ok", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Transfer completed successfully",
                                      "data": {
                                        "status": "OK",
                                        "newSenderBalance": 4500.0000
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
                                      "message": "points: must be positive",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Insufficient balance or sender/recipient cross-tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Insufficient balance", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "INSUFFICIENT_BALANCE",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('CUSTOMER','MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Map<String, Object>>> transfer(@Valid @RequestBody Dtos.TransferRequest req) {
        BigDecimal balance = transferService.transfer(tenantContext.requireTenantId(), req);
        Map<String, Object> data = Map.of("status", "OK", "newSenderBalance", balance);
        return ResponseEntity.ok(ApiResult.ok("Transfer completed successfully", data));
    }

    @PostMapping("/redeem")
    @Operation(summary = "Redeem points (raw, non-voucher)",
            description = "Burns points from the user's main wallet at the merchant's redeem rate without " +
                          "going through a voucher. Used by checkout flows that apply a points discount " +
                          "directly. For voucher-based redemption, use `POST /loyalty/vouchers/redeem`.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Points redeemed",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Points redeemed", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Points redeemed successfully",
                                      "data": {
                                        "status": "OK",
                                        "newBalance": 4500.0000
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
                                      "message": "points: must be positive",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Insufficient balance",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Insufficient balance", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "INSUFFICIENT_BALANCE",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('CUSTOMER','SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Map<String, Object>>> redeem(@Valid @RequestBody Dtos.RedemptionRequest req) {
        // enforceCallerOwnership=true: a CUSTOMER may only redeem their own
        // wallet (admins may act on behalf). The S2S callers use the 3-arg form.
        var result = redemptionService.redeemPoints(tenantContext.requireTenantId(),
                CallerDetails.resolveMerchantId(req.merchantId()), req, true);
        Map<String, Object> data = Map.of(
                "status", "OK",
                "transactionId", result.transactionId(),
                "newBalance", result.balance());
        return ResponseEntity.ok(ApiResult.ok("Points redeemed successfully", data));
    }

    @PostMapping("/convert-to-airtime")
    @Operation(summary = "Convert points to airtime (disabled)",
            description = "Placeholder for the M-Pesa/airtime integration. Returns NOT_ENABLED in this build. " +
                          "Kept on the API surface so client apps can detect feature availability without " +
                          "version sniffing.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Feature flag response",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not enabled", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Airtime conversion not enabled",
                                      "data": {
                                        "status": "NOT_ENABLED",
                                        "message": "M-Pesa / airtime conversion is not enabled in this build."
                                      }
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('CUSTOMER','MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Map<String, Object>>> convertToAirtime() {
        Map<String, Object> data = Map.of(
                "status", "NOT_ENABLED",
                "message", "M-Pesa / airtime conversion is not enabled in this build."
        );
        return ResponseEntity.ok(ApiResult.ok("Airtime conversion not enabled", data));
    }
}
