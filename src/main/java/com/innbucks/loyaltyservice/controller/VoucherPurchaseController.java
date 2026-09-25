package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.VoucherPurchaseService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Voucher purchase orders (V47) — the pay-before-issue flow the console's
 * Issue Voucher screen drives:
 *
 * <ol>
 *   <li>{@code POST /loyalty/vouchers/purchase} — create the order
 *       (everything a direct issue takes, plus the payer's phone). Nothing
 *       is issued yet.</li>
 *   <li>Collect the money: electronic rails go through ticketing's
 *       {@code POST /payments} with {@code orderType=LOYALTY_VOUCHER} +
 *       this {@code orderRef} ({@code paymentRail} omitted = InnBucks 2D
 *       code + QR; {@code ECOCASH} = PIN prompt pushed to the payer's phone;
 *       {@code ZIMSWITCH_CARD} = card widget). Cash is confirmed HERE via
 *       {@code confirm-cash} once the cashier holds the money.</li>
 *   <li>Poll {@code GET .../{orderRef}} until {@code status=PAID} — the
 *       response then carries the issued voucher (code included), and the
 *       recipient's WhatsApp/SMS has been dispatched. The sender's own
 *       confirmation copy goes out only when the order named an explicit
 *       {@code senderPhone}; it is never taken from a staff caller's token.</li>
 * </ol>
 */
@RestController
@RequestMapping("/loyalty/vouchers/purchase")
@Tag(name = "Voucher Purchase", description = "Pay-before-issue voucher orders (V47): create an order, "
        + "collect payment (EcoCash / InnBucks code / card via POST /payments, or cash confirmed here), "
        + "and the voucher is issued on confirmation.")
public class VoucherPurchaseController {

    private final VoucherPurchaseService purchases;
    private final TenantContext tenantContext;

    public VoucherPurchaseController(VoucherPurchaseService purchases, TenantContext tenantContext) {
        this.purchases = purchases;
        this.tenantContext = tenantContext;
    }

    @PostMapping
    @Operation(summary = "Create a voucher purchase order (nothing issued yet)",
            description = "Validates exactly what POST /loyalty/vouchers/issue would (type/usageLimit contract, "
                    + "currency allowlist + in-force FX rate, positive whole-cent value) and snapshots the "
                    + "request — V46 sender identity included — without issuing anything. The response's "
                    + "`orderRef` is what POST /payments takes as `orderType=LOYALTY_VOUCHER` + `orderRef`. "
                    + "`payerPhone` (the EcoCash PIN-prompt target) defaults to the sender's phone, else the "
                    + "assignee's. The order stays payable for 30 minutes by default; payment-service extends "
                    + "that while a code/prompt is live. The caller must administer the issuing merchant "
                    + "(SUPER_ADMIN exempt; SHOP_ADMIN pinned to the merchant in their JWT).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "Order created — collect payment next",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Order created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Purchase order created — collect payment to issue the voucher",
                                      "data": {
                                        "orderRef": "VCH-4F9A1C22B7D3",
                                        "status": "PENDING_PAYMENT",
                                        "amount": 5.0000,
                                        "currency": "USD",
                                        "payerPhone": "+263782608767",
                                        "expiresAt": "2026-09-17T20:15:00Z",
                                        "paidVia": null,
                                        "paidAt": null,
                                        "voucher": null
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error — missing/non-positive/sub-cent value, unsupported currency, "
                            + "no in-force exchange rate, type/usageLimit conflict, no payer phone, or no "
                            + "merchantId when the caller's JWT carries no merchant scope (MERCHANT_REQUIRED). "
                            + "Each of these carries its own domain `code`; a bean-validation failure on the "
                            + "body is instead the generic `400 BAD_REQUEST` / `Validation failed` shape, whose "
                            + "offending fields are in `data`.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "No payer phone", value = """
                                            {
                                              "code": "PAYER_PHONE_REQUIRED",
                                              "message": "Provide payerPhone (or a senderPhone/assigneePhone to default from) — the payment prompt has to reach a real phone.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Unsupported currency", value = """
                                            {
                                              "code": "UNSUPPORTED_CURRENCY",
                                              "message": "Currency GBP is not supported on this cell. Supported: USD, ZAR, ZWG.",
                                              "data": null
                                            }
                                            """)})),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Caller does not administer the issuing merchant",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not merchant owner", value = """
                                    {
                                      "code": "NOT_MERCHANT_OWNER",
                                      "message": "You can only act on merchants you administer.",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "No such merchant in this tenant — a merchant belonging to another tenant "
                            + "reads as absent rather than as a 403, so nothing here confirms its existence",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Unknown merchant", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "merchant not found",
                                      "data": null
                                    }
                                    """)))
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.VoucherPurchaseOrderResponse>> create(
            @Valid @RequestBody Dtos.PurchaseVoucherRequest req) {
        Dtos.VoucherPurchaseOrderResponse data =
                purchases.create(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Purchase order created — collect payment to issue the voucher", data));
    }

    @GetMapping("/{orderRef}")
    @Operation(summary = "Poll a purchase order",
            description = "The console polls this after starting a payment. Once `status` is PAID the "
                    + "response carries the issued voucher (code included) — that is the moment to show "
                    + "the receipt screen. An unpaid order past its deadline reports EXPIRED; create a "
                    + "new order to retry.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Order state",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paid — voucher issued", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Purchase order",
                                      "data": {
                                        "orderRef": "VCH-4F9A1C22B7D3",
                                        "status": "PAID",
                                        "amount": 5.0000,
                                        "currency": "USD",
                                        "payerPhone": "+263782608767",
                                        "expiresAt": "2026-09-17T20:15:00Z",
                                        "paidVia": "GATEWAY",
                                        "paidAt": "2026-09-17T19:52:10Z",
                                        "voucher": {
                                          "id": "c1b7e9f0-9012-3456-0123-456789012345",
                                          "code": "4829137605128368",
                                          "status": "ISSUED",
                                          "voucherType": "SINGLE_USE",
                                          "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "shopId": null,
                                          "batchId": null,
                                          "campaignSource": null,
                                          "assignedUserId": "d2c8f0a1-0123-4567-1234-567890123456",
                                          "assigneePhone": "+263786546765",
                                          "assigneeName": "Sedrick Nyanyiwa",
                                          "senderName": "Tawanda Mpofu",
                                          "senderPhone": "+263782608767",
                                          "issuerUserId": "77777777-7777-7777-7777-777777777777",
                                          "issuerPhone": "+263772000111",
                                          "issuerEmail": "shopadmin@westgate.co.zw",
                                          "usesRemaining": 1,
                                          "value": 5.0000,
                                          "currency": "USD",
                                          "baseValue": 5.0000,
                                          "issuedAt": "2026-09-17T19:52:10Z",
                                          "deliveredAt": "2026-09-17T19:52:15Z",
                                          "viewedAt": null,
                                          "redeemedAt": null,
                                          "transferredAt": null,
                                          "expiresAt": "2027-09-17T19:52:10Z",
                                          "transferredFromUserId": null,
                                          "transferredFromPhone": null
                                        }
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Unknown order in this tenant",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "purchase order not found",
                                      "data": null
                                    }
                                    """)))
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.VoucherPurchaseOrderResponse>> get(
            @PathVariable String orderRef) {
        return ResponseEntity.ok(ApiResult.ok("Purchase order",
                purchases.get(tenantContext.requireTenantId(), orderRef)));
    }

    @PostMapping("/{orderRef}/confirm-cash")
    @Operation(summary = "Confirm a CASH payment and issue the voucher",
            description = "The cashier has the money in hand — their confirmation IS the payment proof, so "
                    + "this is gated on the same staff roles as issuing and records WHO confirmed. Issues "
                    + "the voucher immediately — the recipient's WhatsApp/SMS goes out, and the sender gets "
                    + "their own confirmation copy only when the ORDER named an explicit `senderPhone` (it is "
                    + "never filled in from the confirming cashier's token). Idempotent "
                    + "for a double-click; refused when the order was already paid electronically or has "
                    + "expired (create a new order and take payment again).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Cash confirmed — voucher issued",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Cash confirmed", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Cash payment confirmed — voucher issued",
                                      "data": {
                                        "orderRef": "VCH-4F9A1C22B7D3",
                                        "status": "PAID",
                                        "amount": 5.0000,
                                        "currency": "USD",
                                        "payerPhone": "+263782608767",
                                        "expiresAt": "2026-09-17T20:15:00Z",
                                        "paidVia": "CASH",
                                        "paidAt": "2026-09-17T19:55:00Z",
                                        "voucher": {
                                          "id": "c1b7e9f0-9012-3456-0123-456789012345",
                                          "code": "4829137605128368",
                                          "status": "ISSUED",
                                          "voucherType": "SINGLE_USE",
                                          "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "shopId": null,
                                          "batchId": null,
                                          "campaignSource": null,
                                          "assignedUserId": "d2c8f0a1-0123-4567-1234-567890123456",
                                          "assigneePhone": "+263786546765",
                                          "assigneeName": "Sedrick Nyanyiwa",
                                          "senderName": "Tawanda Mpofu",
                                          "senderPhone": "+263782608767",
                                          "issuerUserId": "77777777-7777-7777-7777-777777777777",
                                          "issuerPhone": "+263772000111",
                                          "issuerEmail": "shopadmin@westgate.co.zw",
                                          "usesRemaining": 1,
                                          "value": 5.0000,
                                          "currency": "USD",
                                          "baseValue": 5.0000,
                                          "issuedAt": "2026-09-17T19:55:00Z",
                                          "deliveredAt": "2026-09-17T19:55:05Z",
                                          "viewedAt": null,
                                          "redeemedAt": null,
                                          "transferredAt": null,
                                          "expiresAt": "2027-09-17T19:55:00Z",
                                          "transferredFromUserId": null,
                                          "transferredFromPhone": null
                                        }
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Already paid electronically, cancelled, or expired",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Already paid electronically", value = """
                                            {
                                              "code": "ORDER_ALREADY_PAID",
                                              "message": "This order was already paid electronically — do not take cash for it.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Expired", value = """
                                            {
                                              "code": "ORDER_EXPIRED",
                                              "message": "This purchase order has expired — create a new one and take payment again.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Cancelled", value = """
                                            {
                                              "code": "ORDER_NOT_CONFIRMABLE",
                                              "message": "This order was cancelled and can no longer be paid.",
                                              "data": null
                                            }
                                            """)})),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Unknown order in this tenant",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "purchase order not found",
                                      "data": null
                                    }
                                    """)))
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.VoucherPurchaseOrderResponse>> confirmCash(
            @PathVariable String orderRef) {
        return ResponseEntity.ok(ApiResult.ok("Cash payment confirmed — voucher issued",
                purchases.confirmCash(tenantContext.requireTenantId(), orderRef)));
    }
}
