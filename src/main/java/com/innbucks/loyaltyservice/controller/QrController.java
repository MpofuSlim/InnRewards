package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.QrService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@RequestMapping("/loyalty/qr")
@Tag(name = "QR",
     description = "Signed QR tokens for in-person flows: a merchant generates a token at the till; the " +
                   "customer scans it with the SuperApp, which calls /consume to award points or complete " +
                   "a transfer. Tokens are HMAC-signed (separate `loyalty.qr.secret`), single-use, and " +
                   "TTL-bounded. Requires X-Tenant-Id.")
public class QrController {

    private final QrService qrService;
    private final TenantContext tenantContext;

    public QrController(QrService qrService, TenantContext tenantContext) {
        this.qrService = qrService;
        this.tenantContext = tenantContext;
    }

    @PostMapping("/issue")
    @Operation(summary = "Issue a signed QR token",
            description = "Generates a token + signature for a specific source (MERCHANT or USER), bound to " +
                          "a transaction type (e.g. QR_PAY) and an amount. The merchant POS or sender app " +
                          "renders this as a QR code. `ttlSeconds` overrides the default TTL configured " +
                          "via `loyalty.qr.ttl-seconds`.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "QR token issued",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "QR issued", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "QR token issued successfully",
                                      "data": {
                                        "token": "qr_2026_e8f7c4d2a1b3",
                                        "signature": "9d3a6c1e8b4f2a7c5d0e2f9a1b8c6d4e3f7a2c5b8d1e4f7a0c3b6d9e2f5a8c1b",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "sourceType": "MERCHANT",
                                        "sourceId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "transactionType": "QR_PAY",
                                        "expiresAt": "2026-05-04T11:05:00Z"
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
                                      "message": "sourceType: must not be null",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('CUSTOMER','MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.QrPayload>> issue(@Valid @RequestBody Dtos.QrIssueRequest req) {
        Dtos.QrPayload data = qrService.issue(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("QR token issued successfully", data));
    }

    @PostMapping("/consume")
    @Operation(summary = "Consume a QR token",
            description = "Verifies the token signature + expiry + single-use flag, then posts the underlying " +
                          "transaction (earn or transfer) on behalf of the scanning user. Reusing the same " +
                          "token returns 4xx — the token is marked CONSUMED on first success.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Token consumed; underlying transaction posted",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Consumed", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "QR token consumed successfully",
                                      "data": {
                                        "id": "44444444-5555-6666-7777-888888888888",
                                        "type": "QR_PAY",
                                        "amount": 100.00,
                                        "pointsDelta": 200.0000,
                                        "balanceAfter": 5300.0000,
                                        "ruleId": "e7f3a5b6-5678-9012-cdef-012345678901",
                                        "campaignId": null,
                                        "reference": "QR:qr_2026_e8f7c4d2a1b3",
                                        "createdAt": "2026-05-04T11:02:00Z"
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
                                      "message": "token: must not be blank",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Token expired, already consumed, or signature mismatch",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Already consumed", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "QR_TOKEN_ALREADY_CONSUMED",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('CUSTOMER','SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TransactionResponse>> consume(@Valid @RequestBody Dtos.QrConsumeRequest req) {
        Dtos.TransactionResponse data = qrService.consume(tenantContext.requireTenantId(), req);
        return ResponseEntity.ok(ApiResult.ok("QR token consumed successfully", data));
    }
}
