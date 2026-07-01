package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.ReportingService;
import com.innbucks.loyaltyservice.service.SuperAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/loyalty/reports")
@Tag(name = "Reporting",
     description = "Read-only dashboards and analytics. Different scopes for different audiences: " +
                   "/operator (platform-wide, no tenant header needed), /tenant (current tenant), " +
                   "/merchant/{id} (one merchant), /user/{id} (SuperApp dashboard). Plus transaction-mix, " +
                   "fraud, and CSV export endpoints.")
public class ReportingController {

    private final ReportingService reporting;
    private final SuperAppService superApp;
    private final TenantContext tenantContext;

    public ReportingController(ReportingService reporting, SuperAppService superApp,
                               TenantContext tenantContext) {
        this.reporting = reporting;
        this.superApp = superApp;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/operator")
    @Operation(summary = "Operator dashboard (platform-wide)",
            description = "Cross-tenant aggregates for the platform operator: total tenants, active " +
                          "merchants, transactions/vouchers/points today, fraud attempts in last 24h, " +
                          "invoice and voucher-expiry counts. No tenant header required.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Operator dashboard",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Operator dashboard", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Operator dashboard retrieved successfully",
                                      "data": {
                                        "totalTenants": 14,
                                        "activeMerchants": 132,
                                        "transactionsToday": 4821,
                                        "vouchersIssuedToday": 612,
                                        "vouchersRedeemedToday": 388,
                                        "pointsIssuedToday": 152340.0000,
                                        "pointsRedeemedToday": 47820.0000,
                                        "fraudAttempts24h": 27,
                                        "invoicesPending": 23,
                                        "invoicesPaid": 411,
                                        "expiringIn7Days": 84,
                                        "expiringIn30Days": 921
                                      }
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.OperatorDashboard>> operator() {
        Dtos.OperatorDashboard data = reporting.operator();
        return ResponseEntity.ok(ApiResult.ok("Operator dashboard retrieved successfully", data));
    }

    @GetMapping("/tenant")
    @Operation(summary = "Tenant dashboard",
            description = "Per-tenant rollup for the current X-Tenant-Id: merchants, active campaigns, " +
                          "outstanding/expired vouchers, total wallet balance, pending invoices.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Tenant dashboard",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Tenant dashboard", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Tenant dashboard retrieved successfully",
                                      "data": {
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "merchants": 24,
                                        "activeCampaigns": 3,
                                        "vouchersOutstanding": 1842,
                                        "vouchersExpired": 305,
                                        "totalWalletBalance": 4218750.0000,
                                        "invoicesPending": 4
                                      }
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TenantDashboard>> tenant() {
        Dtos.TenantDashboard data = reporting.tenant(tenantContext.requireTenantId());
        return ResponseEntity.ok(ApiResult.ok("Tenant dashboard retrieved successfully", data));
    }

    @GetMapping("/merchant/{id}")
    @Operation(summary = "Merchant dashboard",
            description = "Per-merchant operational view: redemptions today, vouchers issued/redeemed, points " +
                          "issued/redeemed, fraud alerts in last 24h, next invoice date and estimated amount.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Merchant dashboard",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Merchant dashboard", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Merchant dashboard retrieved successfully",
                                      "data": {
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "redemptionsToday": 38,
                                        "vouchersIssued": 612,
                                        "vouchersRedeemed": 388,
                                        "pointsIssued": 12500.0000,
                                        "pointsRedeemed": 4300.0000,
                                        "fraudAlerts24h": 2,
                                        "nextInvoiceDate": "2026-06-01",
                                        "estimatedInvoice": 245.50
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Merchant not found",
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
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Merchant belongs to a different tenant than the X-Tenant-Id header",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Cross-tenant", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "merchant belongs to a different tenant",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.MerchantDashboard>> merchant(@PathVariable UUID id) {
        Dtos.MerchantDashboard data = reporting.merchant(tenantContext.requireTenantId(), id);
        return ResponseEntity.ok(ApiResult.ok("Merchant dashboard retrieved successfully", data));
    }

    @GetMapping("/user/{id}")
    @Operation(summary = "SuperApp user dashboard",
            description = "Customer-facing dashboard: total points across all wallets, the wallet list, " +
                          "active vouchers, and recent transactions. The {id} is the LoyaltyUser UUID, not " +
                          "the user-service userId. Requires X-Tenant-Id and only resolves a user that " +
                          "belongs to that tenant — a request for a user in another tenant is rejected with " +
                          "403 CROSS_TENANT (SUPER_ADMIN may target any tenant via the header).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "User dashboard",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "User dashboard", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "User dashboard retrieved successfully",
                                      "data": {
                                        "userId": "d2c8f0a1-0123-4567-1234-567890123456",
                                        "totalPoints": 5300.0000,
                                        "wallets": [
                                          {
                                            "id": "w1a2b3c4-d5e6-f708-1929-3a4b5c6d7e8f",
                                            "userId": "d2c8f0a1-0123-4567-1234-567890123456",
                                            "label": "Main",
                                            "type": "STANDARD",
                                            "pocket": "MAIN",
                                            "balance": 4800.0000,
                                            "lockedUntil": null
                                          },
                                          {
                                            "id": "w2b3c4d5-e6f7-0819-2a3b-4c5d6e7f8091",
                                            "userId": "d2c8f0a1-0123-4567-1234-567890123456",
                                            "label": "Holiday Savings",
                                            "type": "LOCKED",
                                            "pocket": "SAVINGS",
                                            "balance": 500.0000,
                                            "lockedUntil": "2026-12-24"
                                          }
                                        ],
                                        "activeVouchers": [
                                          {
                                            "id": "c1b7e9f0-9012-3456-0123-456789012345",
                                            "code": "VCH-AB12CD34",
                                            "status": "ISSUED",
                                            "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                            "assignedUserId": "d2c8f0a1-0123-4567-1234-567890123456",
                                            "assigneePhone": "+254700000000",
                                            "usesRemaining": 1,
                                            "issuedAt": "2026-05-04T10:30:00Z",
                                            "expiresAt": "2026-06-03T10:30:00Z"
                                          }
                                        ],
                                        "recentTransactions": [
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
                                          }
                                        ]
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "User not found",
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
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "User belongs to a different tenant than the X-Tenant-Id header",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Cross-tenant", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "user belongs to a different tenant",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.UserDashboard>> user(@PathVariable UUID id) {
        Dtos.UserDashboard data = superApp.dashboard(tenantContext.requireTenantId(), id);
        return ResponseEntity.ok(ApiResult.ok("User dashboard retrieved successfully", data));
    }

    @GetMapping("/user/phone/{phone}")
    @Operation(summary = "SuperApp user dashboard (by phone number)",
            description = "Identical to GET /reports/user/{id}, but resolved by the customer's phone " +
                          "number instead of the LoyaltyUser UUID — the phone is the stable identifier " +
                          "the SuperApp or a CS agent actually has. Pass the stored E.164 form " +
                          "(e.g. +263771234567; URL-encode the leading + as %2B if your client requires " +
                          "it). Tenant-scoped: only a customer that belongs to the X-Tenant-Id is " +
                          "resolvable, so a phone that exists only under another tenant returns 404 " +
                          "(it does not reveal cross-tenant existence). Returns the same payload — note " +
                          "the response `userId` is still the LoyaltyUser UUID.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "User dashboard",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "User dashboard", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "User dashboard retrieved successfully",
                                      "data": {
                                        "userId": "d2c8f0a1-0123-4567-1234-567890123456",
                                        "totalPoints": 5300.0000,
                                        "wallets": [
                                          {
                                            "id": "w1a2b3c4-d5e6-f708-1929-3a4b5c6d7e8f",
                                            "userId": "d2c8f0a1-0123-4567-1234-567890123456",
                                            "label": "Main",
                                            "type": "STANDARD",
                                            "pocket": "MAIN",
                                            "balance": 4800.0000,
                                            "lockedUntil": null
                                          }
                                        ],
                                        "activeVouchers": [],
                                        "recentTransactions": [
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
                                          }
                                        ]
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Blank phone number",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Blank phone", value = """
                                    {
                                      "code": "BAD_PHONE",
                                      "message": "Please provide a phone number.",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "No customer with that phone number in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "user not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.UserDashboard>> userByPhone(@PathVariable String phone) {
        Dtos.UserDashboard data = superApp.dashboardByPhone(tenantContext.requireTenantId(), phone);
        return ResponseEntity.ok(ApiResult.ok("User dashboard retrieved successfully", data));
    }

    @GetMapping("/transactions/mix")
    @Operation(summary = "Transaction mix (counts per type)",
            description = "Returns a map of `TransactionType -> count` for the (optional) merchant within " +
                          "[from, to]. Use for revenue-mix charts. `from` and `to` are required ISO dates.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Transaction mix",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Mix", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Transaction mix retrieved successfully",
                                      "data": {
                                        "PURCHASE": 1842,
                                        "QR_PAY": 612,
                                        "REDEMPTION": 388,
                                        "REFUND": 14,
                                        "ADJUSTMENT": 7,
                                        "TRANSFER": 32
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Missing/invalid date range",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Bad date", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "from: failed to convert value of type 'java.lang.String'",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Map<String, Long>>> transactionMix(@RequestParam(required = false) UUID merchantId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Map<String, Long> data = reporting.transactionMix(tenantContext.requireTenantId(), merchantId, from, to);
        return ResponseEntity.ok(ApiResult.ok("Transaction mix retrieved successfully", data));
    }

    @GetMapping("/points/merchant/{merchantId}")
    @Operation(summary = "Points report (per merchant, period-bounded)",
            description = "Sum of points issued and redeemed at this merchant between `from` and `to` (UTC " +
                          "calendar days, inclusive). Complements `/reports/merchant/{id}` which is hardcoded " +
                          "to today only. `netPoints = pointsIssued - pointsRedeemed`.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Points report",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Points report", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Points report retrieved successfully",
                                      "data": {
                                        "subjectId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "from": "2026-05-01",
                                        "to": "2026-05-31",
                                        "pointsIssued": 152340.0000,
                                        "pointsRedeemed": 47820.0000,
                                        "netPoints": 104520.0000,
                                        "transactionCount": 1872
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Missing/invalid/inverted date range",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Range inverted", value = """
                                    {
                                      "code": "RANGE_INVERTED",
                                      "message": "from must not be after to",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Merchant belongs to a different tenant than the X-Tenant-Id header",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Cross-tenant", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "merchant belongs to a different tenant",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.PointsReport>> pointsForMerchant(
            @PathVariable UUID merchantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Dtos.PointsReport data = reporting.pointsForMerchant(
                tenantContext.requireTenantId(), merchantId, from, to);
        return ResponseEntity.ok(ApiResult.ok("Points report retrieved successfully", data));
    }

    @GetMapping("/points/user/{userId}")
    @Operation(summary = "Points report (per user, period-bounded)",
            description = "Sum of points earned and spent by this LoyaltyUser between `from` and `to`. " +
                          "Complements the SuperApp dashboard's `totalPoints` (current balance, no period) " +
                          "and `recentTransactions` (raw list) — useful for a customer's monthly statement " +
                          "or a CS agent investigating a balance dispute.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Points report",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Points report", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Points report retrieved successfully",
                                      "data": {
                                        "subjectId": "d2c8f0a1-0123-4567-1234-567890123456",
                                        "from": "2026-05-01",
                                        "to": "2026-05-31",
                                        "pointsIssued": 1240.0000,
                                        "pointsRedeemed": 500.0000,
                                        "netPoints": 740.0000,
                                        "transactionCount": 18
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "User belongs to a different tenant than the X-Tenant-Id header",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Cross-tenant", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "user belongs to a different tenant",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.PointsReport>> pointsForUser(
            @PathVariable UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Dtos.PointsReport data = reporting.pointsForUser(tenantContext.requireTenantId(), userId, from, to);
        return ResponseEntity.ok(ApiResult.ok("Points report retrieved successfully", data));
    }

    @GetMapping("/points/user/phone/{phone}")
    @Operation(summary = "Points report (per user, by phone number, period-bounded)",
            description = "Identical to GET /reports/points/user/{userId}, but resolved by the customer's " +
                          "phone number instead of the LoyaltyUser UUID. Pass the stored E.164 form " +
                          "(e.g. +263771234567; URL-encode the leading + as %2B if your client requires " +
                          "it). Tenant-scoped: a phone that exists only under another tenant returns 404. " +
                          "The response `subjectId` is still the resolved LoyaltyUser UUID.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Points report",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Points report", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Points report retrieved successfully",
                                      "data": {
                                        "subjectId": "d2c8f0a1-0123-4567-1234-567890123456",
                                        "from": "2026-05-01",
                                        "to": "2026-05-31",
                                        "pointsIssued": 1240.0000,
                                        "pointsRedeemed": 500.0000,
                                        "netPoints": 740.0000,
                                        "transactionCount": 18
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Blank phone number or missing/inverted date range",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Blank phone", value = """
                                    {
                                      "code": "BAD_PHONE",
                                      "message": "Please provide a phone number.",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "No customer with that phone number in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "user not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.PointsReport>> pointsForUserByPhone(
            @PathVariable String phone,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Dtos.PointsReport data = reporting.pointsForUserByPhone(tenantContext.requireTenantId(), phone, from, to);
        return ResponseEntity.ok(ApiResult.ok("Points report retrieved successfully", data));
    }

    @GetMapping("/points/shop/{shopId}")
    @Operation(summary = "Points report (per shop, period-bounded, with per-customer breakdown)",
            description = "Points issued / redeemed at this outlet between `from` and `to`, PLUS a " +
                          "`byPhone` breakdown of points per customer phone number (highest earners " +
                          "first). Only transactions stamped with `shopId` count — the ones produced by " +
                          "the shop-checkout / guest-checkout flow. Used by the per-outlet leaderboard " +
                          "on the merchant dashboard.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Points report",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Points report", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Points report retrieved successfully",
                                      "data": {
                                        "subjectId": "c7d8e9f0-1234-5678-90ab-cdef12345678",
                                        "from": "2026-05-01",
                                        "to": "2026-05-31",
                                        "pointsIssued": 18240.0000,
                                        "pointsRedeemed": 5320.0000,
                                        "netPoints": 12920.0000,
                                        "transactionCount": 312,
                                        "byPhone": [
                                          {
                                            "phoneNumber": "+263772222222",
                                            "pointsIssued": 12000.0000,
                                            "pointsRedeemed": 3200.0000,
                                            "netPoints": 8800.0000,
                                            "transactionCount": 190
                                          },
                                          {
                                            "phoneNumber": "+263771111111",
                                            "pointsIssued": 6240.0000,
                                            "pointsRedeemed": 2120.0000,
                                            "netPoints": 4120.0000,
                                            "transactionCount": 122
                                          }
                                        ]
                                      }
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ShopPointsReport>> pointsForShop(
            @PathVariable UUID shopId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Dtos.ShopPointsReport data = reporting.pointsForShop(
                tenantContext.requireTenantId(), shopId, from, to);
        return ResponseEntity.ok(ApiResult.ok("Points report retrieved successfully", data));
    }

    @GetMapping("/points/by-type")
    @Operation(summary = "Points by transaction type",
            description = "Per-type breakdown over a date range, returning count + pointsIssued + " +
                          "pointsRedeemed for each `TransactionType`. The existing `/transactions/mix` " +
                          "endpoint returns counts only (kept for backwards-compat); this one is the " +
                          "report your donut-chart actually wants. Optional `merchantId` narrows to one " +
                          "merchant; omit it for tenant-wide.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Per-type rows",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "By type", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Points by type retrieved successfully",
                                      "data": [
                                        {"type": "PURCHASE",   "count": 1842, "pointsIssued": 184200.0000, "pointsRedeemed":     0.0000},
                                        {"type": "REDEMPTION", "count":  388, "pointsIssued":      0.0000, "pointsRedeemed": 38800.0000},
                                        {"type": "ADJUSTMENT", "count":    7, "pointsIssued":    250.0000, "pointsRedeemed":     0.0000}
                                      ]
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<List<Dtos.PointsByTypeRow>>> pointsByType(
            @RequestParam(required = false) UUID merchantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<Dtos.PointsByTypeRow> data = reporting.pointsByType(
                tenantContext.requireTenantId(), merchantId, from, to);
        return ResponseEntity.ok(ApiResult.ok("Points by type retrieved successfully", data));
    }

    @GetMapping("/points/time-series")
    @Operation(summary = "Points time-series (daily buckets)",
            description = "One row per UTC calendar day in [from, to], each with pointsIssued / " +
                          "pointsRedeemed / transactionCount. Days with no activity are filled with zeros " +
                          "so the FE always gets a contiguous series and can render a chart without holes. " +
                          "Optional `merchantId` narrows to one merchant. Cap the range at a few months — " +
                          "the response is one JSON object per day, no streaming.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Daily series",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Series", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Points time-series retrieved successfully",
                                      "data": [
                                        {"bucket": "2026-05-01T00:00:00Z", "pointsIssued": 5120.0000, "pointsRedeemed": 1240.0000, "transactionCount": 73},
                                        {"bucket": "2026-05-02T00:00:00Z", "pointsIssued":    0.0000, "pointsRedeemed":    0.0000, "transactionCount":  0},
                                        {"bucket": "2026-05-03T00:00:00Z", "pointsIssued": 4280.0000, "pointsRedeemed":  500.0000, "transactionCount": 61}
                                      ]
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<List<Dtos.PointsTimeSeriesPoint>>> pointsTimeSeries(
            @RequestParam(required = false) UUID merchantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<Dtos.PointsTimeSeriesPoint> data = reporting.pointsTimeSeries(
                tenantContext.requireTenantId(), merchantId, from, to);
        return ResponseEntity.ok(ApiResult.ok("Points time-series retrieved successfully", data));
    }

    @GetMapping("/fraud")
    @Operation(summary = "Recent fraud attempts",
            description = "Returns rejected redemption attempts (signature mismatch, expired, duplicate, " +
                          "wrong-merchant, blocked-user, blocked-device) for the current tenant. Used by " +
                          "compliance dashboards and to triage velocity-blocked users.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Fraud attempts returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated fraud", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Fraud attempts retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "fa1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9",
                                            "voucherCode": "VCH-AB12CD34",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "reason": "WRONG_MERCHANT",
                                            "detail": "voucher scoped to merchant c5d1e3f4 but presented at b4c0d2e3",
                                            "deviceFingerprint": "fp-deadbeef-0001",
                                            "createdAt": "2026-05-04T13:42:00Z"
                                          },
                                          {
                                            "id": "fa2c3d4e-5f60-7182-93a4-b5c6d7e8f900",
                                            "voucherCode": "VCH-EF56GH78",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "reason": "VELOCITY_BLOCKED",
                                            "detail": "5 redemptions from device fp-cafebabe-0009 in 60s",
                                            "deviceFingerprint": "fp-cafebabe-0009",
                                            "createdAt": "2026-05-04T14:11:00Z"
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
    public ResponseEntity<ApiResult<PageResponse<Dtos.FraudAttemptResponse>>> fraud(@ParameterObject Pageable pageable) {
        PageResponse<Dtos.FraudAttemptResponse> data = PageResponse.from(
                reporting.recentFraud(tenantContext.requireTenantId()), pageable);
        return ResponseEntity.ok(ApiResult.ok("Fraud attempts retrieved successfully", data));
    }

    @GetMapping(value = "/transactions/export", produces = "text/csv")
    @Operation(summary = "Export transactions as CSV",
            description = "Streams a CSV of every transaction in [from, to], optionally scoped to a single " +
                          "merchant. Returns `Content-Disposition: attachment; filename=transactions.csv` so " +
                          "browsers download rather than render. Sorted ascending by `createdAt` for " +
                          "deterministic output. `balanceAfter` is intentionally absent — it's computed " +
                          "at write time and not stored, so an after-the-fact export can't reconstruct it.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "CSV body (no ApiResult envelope)",
                    content = @Content(
                            mediaType = "text/csv",
                            schema = @Schema(type = "string", format = "binary"),
                            examples = @ExampleObject(name = "CSV", value = """
                                    id,createdAt,type,amount,pointsDelta,merchantId,shopId,userId,reference
                                    11111111-2222-3333-4444-555555555555,2026-05-04T11:00:00Z,PURCHASE,100.00,100.0000,b4c0d2e3-2345-6789-abcd-ef0123456789,c7d8e9f0-1234-5678-90ab-cdef12345678,d2c8f0a1-0123-4567-1234-567890123456,POS-20260504-0001
                                    22222222-3333-4444-5555-666666666666,2026-05-04T12:00:00Z,REDEMPTION,,-500.0000,b4c0d2e3-2345-6789-abcd-ef0123456789,c7d8e9f0-1234-5678-90ab-cdef12345678,d2c8f0a1-0123-4567-1234-567890123456,VOUCHER:VCH-AB12CD
                                    33333333-4444-5555-6666-777777777777,2026-05-04T13:15:00Z,ADJUSTMENT,,250.0000,b4c0d2e3-2345-6789-abcd-ef0123456789,,d2c8f0a1-0123-4567-1234-567890123456,Goodwill credit
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Missing/invalid date range"
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<String> exportCsv(@RequestParam(required = false) UUID merchantId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String csv = reporting.csv(tenantContext.requireTenantId(), merchantId, from, to);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition", "attachment; filename=\"transactions.csv\"")
                .body(csv);
    }
}
