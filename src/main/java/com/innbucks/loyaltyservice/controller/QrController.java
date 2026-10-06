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
    private final com.innbucks.loyaltyservice.service.EligibilityDeferral eligibilityDeferral;

    public QrController(QrService qrService, TenantContext tenantContext,
                        com.innbucks.loyaltyservice.service.EligibilityDeferral eligibilityDeferral) {
        this.qrService = qrService;
        this.tenantContext = tenantContext;
        this.eligibilityDeferral = eligibilityDeferral;
    }

    @PostMapping("/issue")
    @Operation(summary = "Issue a signed QR token",
            description = "Generates a token + signature for a specific source (MERCHANT or USER), bound to " +
                          "a transaction type (e.g. QR_PAY) and an amount. The merchant POS or sender app " +
                          "renders this as a QR code. `ttlSeconds` overrides the default TTL configured " +
                          "via `loyalty.qr.ttl-seconds`. A MERCHANT QR issued by shop staff remembers the " +
                          "issuer's `shopId`, and the earn its scan produces is attributed to that shop. Poll " +
                          "`POST /loyalty/qr/status` to learn when it has been scanned.")
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
                    description = "Bean-validation failure (field detail in `data`), INVALID_AMOUNT for a "
                            + "negative `amount`, or UNSUPPORTED_CURRENCY when `currency` is outside the "
                            + "cell's allowlist.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": { "sourceType": "must not be null" }
                                    }
                                    """),
                                    @ExampleObject(name = "Negative amount", value = """
                                    {
                                      "code": "INVALID_AMOUNT",
                                      "message": "amount must not be negative",
                                      "data": null
                                    }
                                    """),
                                    @ExampleObject(name = "Unsupported currency", value = """
                                    {
                                      "code": "UNSUPPORTED_CURRENCY",
                                      "message": "Currency GBP is not supported on this cell. Supported: USD, ZAR, ZWG.",
                                      "data": null
                                    }
                                    """)}
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "NOT_MERCHANT_OWNER — a MERCHANT-sourced QR whose `sourceId` names a "
                            + "merchant the caller does not administer; or NOT_WALLET_OWNER — a USER-sourced "
                            + "QR whose `sourceId` is not the caller's own loyalty account.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Not the merchant's admin", value = """
                                    {
                                      "code": "NOT_MERCHANT_OWNER",
                                      "message": "You can only act on merchants you administer.",
                                      "data": null
                                    }
                                    """),
                                    @ExampleObject(name = "Not the wallet owner", value = """
                                    {
                                      "code": "NOT_WALLET_OWNER",
                                      "message": "you can only act on your own loyalty account",
                                      "data": null
                                    }
                                    """)}
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "The merchant or user named by `sourceId` does not exist in this tenant. "
                            + "A cross-tenant id is reported as absent rather than forbidden, so it is "
                            + "never an existence oracle.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Unknown source", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "merchant not found",
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
                          "token returns 4xx — the token is marked CONSUMED on first success. A MERCHANT QR's " +
                          "earn carries the ISSUING till's `shopId` (never the scanner's), so it appears in " +
                          "that shop's `GET /loyalty/transactions/my-shop`.")
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
                                        "shopId": "c7d8e9f0-1234-5678-90ab-cdef12345678",
                                        "postedBy": "11111111-2222-3333-4444-555555555555",
                                        "channel": "QR_PRESENCE",
                                        "reference": "QR:qr_2026_e8f7c4d2a1b3",
                                        "createdAt": "2026-05-04T11:02:00Z",
                                        "invoiceId": null
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error, or QR_EXPIRED when the token is past its TTL.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Expired token", value = """
                                    {
                                      "code": "QR_EXPIRED",
                                      "message": "This QR code has expired.",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "QR_REUSED — the token has already been consumed. Single-use, even inside "
                            + "its TTL.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Already consumed", value = """
                                    {
                                      "code": "QR_REUSED",
                                      "message": "This QR code has already been used.",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "BAD_SIGNATURE (the token failed HMAC verification), CROSS_TENANT, or a "
                            + "MERCHANT QR scanned by that merchant's own staff: SELF_EARN when the caller's "
                            + "token is scoped to the QR's merchant, STAFF_RECIPIENT when the credited phone "
                            + "belongs to one of its staff (whatever token they scan with). A refused scan "
                            + "leaves the QR unused, so the customer it was shown to can still scan it.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Bad signature", value = """
                                            {
                                              "code": "BAD_SIGNATURE",
                                              "message": "This QR code couldn't be verified.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Staff scanned their own till's QR", value = """
                                            {
                                              "code": "SELF_EARN",
                                              "message": "You can't award points to your own account.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Credited phone is merchant staff", value = """
                                            {
                                              "code": "STAFF_RECIPIENT",
                                              "message": "Points can't be awarded to a staff account of this merchant.",
                                              "data": null
                                            }
                                            """)
                            }
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "The token is unknown.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Unknown token", value = """
                                    {
                                      "code": "NOT_FOUND",
                                      "message": "This QR code is invalid or has expired.",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    // No SHOP_USER: consume credits the CALLER, and a till token has no reason
    // to credit itself. The service still refuses a merchant's own staff
    // scanning its QR under any other role (SELF_EARN / STAFF_RECIPIENT).
    @PreAuthorize("hasAnyRole('CUSTOMER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TransactionResponse>> consume(@Valid @RequestBody Dtos.QrConsumeRequest req) {
        java.util.UUID tenantId = tenantContext.requireTenantId();
        // Load the STAFF_RECIPIENT snapshot before consume() locks the QR row,
        // so the guard under that lock never waits on user-service. Never refuses.
        qrService.prewarmStaffRecipientGuard(tenantId, req.token());
        // A transfer-QR is a spend by its sender: the V44 eligibility check runs
        // outside the consume's transaction and QR row lock (EligibilityDeferral).
        Dtos.TransactionResponse data = eligibilityDeferral.run(() -> qrService.consume(tenantId, req));
        return ResponseEntity.ok(ApiResult.ok("QR token consumed successfully", data));
    }

    @PostMapping("/status")
    @Operation(summary = "Check whether a QR token was scanned",
            description = "Lets the till (or the app) that issued a QR learn what became of it: PENDING "
                          + "(not scanned, still inside its TTL), CONSUMED (scanned — with when, the earn "
                          + "`transactionId` and the `pointsAwarded`) or EXPIRED (its TTL passed unscanned). "
                          + "Poll it after showing a QR; stop on anything but PENDING.\n\n"
                          + "The token goes in the BODY, never the URL: it is a consumable credential and "
                          + "URLs are logged. No signature is needed — this reads, it never consumes.\n\n"
                          + "**Who may see a QR.** A MERCHANT QR: staff of its merchant — SHOP_ADMIN / "
                          + "SHOP_USER by the merchant in their token (any till of that merchant), "
                          + "MERCHANT_ADMIN by organization, SUPER_ADMIN always. A USER (transfer) QR: its "
                          + "sender only. Anything else is the same 404 as an unknown token, so the endpoint "
                          + "never confirms a QR exists. Requires X-Tenant-Id; a token of another tenant is "
                          + "also that 404.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "The QR's current state. `consumedAt`, `transactionId` and `pointsAwarded` "
                            + "are null until it is scanned; `transactionId` stays null for a transfer QR.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Scanned", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "QR status retrieved successfully",
                                      "data": {
                                        "status": "CONSUMED",
                                        "expiresAt": "2026-05-04T11:05:00Z",
                                        "consumedAt": "2026-05-04T11:02:00Z",
                                        "transactionId": "44444444-5555-6666-7777-888888888888",
                                        "pointsAwarded": 200.0000
                                      }
                                    }
                                    """),
                                    @ExampleObject(name = "Not scanned yet", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "QR status retrieved successfully",
                                      "data": {
                                        "status": "PENDING",
                                        "expiresAt": "2026-05-04T11:05:00Z",
                                        "consumedAt": null,
                                        "transactionId": null,
                                        "pointsAwarded": null
                                      }
                                    }
                                    """),
                                    @ExampleObject(name = "Expired unscanned", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "QR status retrieved successfully",
                                      "data": {
                                        "status": "EXPIRED",
                                        "expiresAt": "2026-05-04T11:05:00Z",
                                        "consumedAt": null,
                                        "transactionId": null,
                                        "pointsAwarded": null
                                      }
                                    }
                                    """)}
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Bean validation (`token` blank or over 64 characters; field detail in "
                            + "`data`), or MISSING_TENANT when neither X-Tenant-Id nor X-Tenant-Code is sent.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": { "token": "must not be blank" }
                                    }
                                    """),
                                    @ExampleObject(name = "No tenant header", value = """
                                    {
                                      "code": "MISSING_TENANT",
                                      "message": "X-Tenant-Id or X-Tenant-Code header is required",
                                      "data": null
                                    }
                                    """)}
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "The caller's role may not use this endpoint, or the caller is not a member "
                            + "of the tenant. A QR the caller may not see is NOT a 403 — it is the 404 below.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not permitted", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "You don't have permission to do that.",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "The token is unknown, belongs to another tenant, or is a QR the caller may "
                            + "not see (another merchant's QR, someone else's transfer QR). One answer for all "
                            + "three — the same one POST /consume gives an unknown token.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Unknown or not yours", value = """
                                    {
                                      "code": "NOT_FOUND",
                                      "message": "This QR code is invalid or has expired.",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('CUSTOMER','SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.QrStatusResponse>> status(@Valid @RequestBody Dtos.QrStatusRequest req) {
        Dtos.QrStatusResponse data = qrService.status(tenantContext.requireTenantId(), req.token());
        return ResponseEntity.ok(ApiResult.ok("QR status retrieved successfully", data));
    }
}
