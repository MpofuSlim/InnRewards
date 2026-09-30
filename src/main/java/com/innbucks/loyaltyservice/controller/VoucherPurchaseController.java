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
 *       {@code confirm-cash} once the cashier holds the money, and a card
 *       swiped on the till's own machine via {@code confirm-card} with the
 *       slip's approval code (V54).</li>
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
        + "collect payment (EcoCash / InnBucks code / card via POST /payments, or cash / the till's card "
        + "machine confirmed here), "
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
                    + "(SUPER_ADMIN exempt; SHOP_ADMIN and SHOP_USER (cashiers) pinned to the merchant in their JWT). "
                    + "Cashiers sell vouchers through this paid flow; the free POST /loyalty/vouchers/issue stays "
                    + "admin-only.")
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
                            + "no in-force exchange rate, type/usageLimit conflict, no payer phone, a sender phone that is the "
                            + "recipient's own number (SENDER_IS_RECIPIENT), or no "
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
                                    @ExampleObject(name = "Sender is the recipient", value = """
                                            {
                                              "code": "SENDER_IS_RECIPIENT",
                                              "message": "The sender and the recipient can't be the same phone number. Enter the recipient's number, or leave the sender blank if the voucher is for the customer themselves.",
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
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SHOP_USER','SUPER_ADMIN')")
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
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SHOP_USER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.VoucherPurchaseOrderResponse>> get(
            @PathVariable String orderRef) {
        return ResponseEntity.ok(ApiResult.ok("Purchase order",
                purchases.get(tenantContext.requireTenantId(), orderRef)));
    }

    @PostMapping("/{orderRef}/confirm-cash")
    @Operation(summary = "Confirm a CASH payment and issue the voucher",
            description = "The cashier has the money in hand — their confirmation IS the payment proof, so "
                    + "it records WHO confirmed. Cashiers (SHOP_USER) may confirm, for orders raised at their "
                    + "own shop, except on a voucher to, from or paid by their own phone or to a staff member "
                    + "(403 SELF_CONFIRM / STAFF_RECIPIENT). Issues "
                    + "the voucher immediately — the recipient's WhatsApp/SMS goes out, and the sender gets "
                    + "their own confirmation copy only when the ORDER named an explicit `senderPhone` (it is "
                    + "never filled in from the confirming cashier's token). Idempotent "
                    + "for a double-click; refused when the order was already paid another way or has "
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
                                        },
                                        "electronicPaymentUntil": null,
                                        "cardApprovalCode": null
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "An electronic payment for this order may still complete "
                            + "(ELECTRONIC_PAYMENT_PENDING — wait for `electronicPaymentUntil` to pass), or the "
                            + "order was already paid (electronically or on the card machine), cancelled, or expired",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Electronic payment still live", value = """
                                            {
                                              "code": "ELECTRONIC_PAYMENT_PENDING",
                                              "message": "An EcoCash, InnBucks or card payment for this order is still waiting for the customer. Don't take cash now: let the customer finish paying, or wait about 4 minutes for it to lapse and try again.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Already paid electronically", value = """
                                            {
                                              "code": "ORDER_ALREADY_PAID",
                                              "message": "This order was already paid electronically — do not take cash for it.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Already paid on the card machine", value = """
                                            {
                                              "code": "ORDER_ALREADY_PAID",
                                              "message": "This order was already paid on the card machine — do not take cash for it.",
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
                    responseCode = "403",
                    description = "Shop staff (a cashier or shop admin) confirming a cash payment on a voucher "
                            + "to, from or paid by their own phone, or for a recipient on the merchant's staff "
                            + "list; or a caller outside this merchant",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Shop staff on their own voucher", value = """
                                            {
                                              "code": "SELF_CONFIRM",
                                              "message": "You can't confirm a cash or card payment for a voucher to or from your own phone. Pay by EcoCash or InnBucks, or ask a merchant admin to confirm it.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Recipient is a staff member", value = """
                                            {
                                              "code": "STAFF_RECIPIENT",
                                              "message": "This voucher is for a staff member, so shop staff can't confirm a cash or card payment for it. Pay by EcoCash or InnBucks, or ask a merchant admin to confirm it.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Another merchant", value = """
                                            {
                                              "code": "NOT_MERCHANT_OWNER",
                                              "message": "You can only act on merchants you administer.",
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
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SHOP_USER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.VoucherPurchaseOrderResponse>> confirmCash(
            @PathVariable String orderRef) {
        return ResponseEntity.ok(ApiResult.ok("Cash payment confirmed — voucher issued",
                purchases.confirmCash(tenantContext.requireTenantId(), orderRef)));
    }

    @PostMapping("/{orderRef}/confirm-card")
    @Operation(summary = "Confirm a card swiped on the till's card machine and issue the voucher",
            description = "For a card swiped on the shop's OWN card machine (not the online ZimSwitch card "
                    + "checkout, which goes through POST /payments). The machine is outside our systems, so — "
                    + "like cash — the cashier's confirmation is the payment proof: same staff roles, WHO "
                    + "confirmed is recorded, the voucher issues immediately, and the same double-payment and "
                    + "expiry guards apply. Two extra rules: the `approvalCode` from the machine's slip is "
                    + "REQUIRED (it reconciles the voucher against the bank's card settlement), and the order "
                    + "must be priced in a currency the card machines settle in "
                    + "(`loyalty.voucher.card-pos-currencies`, USD and ZWG on the ZW cell). Idempotent for a "
                    + "double-click with the same approval code.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Card payment confirmed — voucher issued",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Card confirmed", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Card payment confirmed — voucher issued",
                                      "data": {
                                        "orderRef": "VCH-4F9A1C22B7D3",
                                        "status": "PAID",
                                        "amount": 5.0000,
                                        "currency": "USD",
                                        "payerPhone": "+263782608767",
                                        "expiresAt": "2026-09-17T20:15:00Z",
                                        "paidVia": "CARD_POS",
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
                                        },
                                        "electronicPaymentUntil": null,
                                        "cardApprovalCode": "A1B2C3"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Approval code missing or malformed, or a bad last4",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "No approval code", value = """
                                            {
                                              "code": "APPROVAL_CODE_REQUIRED",
                                              "message": "Enter the approval code printed on the card machine slip (4 to 12 letters or digits).",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Bad last4", value = """
                                            {
                                              "code": "INVALID_CARD_LAST4",
                                              "message": "last4 must be exactly the last four digits of the card, or left out.",
                                              "data": null
                                            }
                                            """)})),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "An electronic payment for this order may still complete, or the order was "
                            + "already paid, cancelled, or expired",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Electronic payment still live", value = """
                                            {
                                              "code": "ELECTRONIC_PAYMENT_PENDING",
                                              "message": "An EcoCash, InnBucks or card payment for this order is still waiting for the customer. Don't swipe the card now: let the customer finish paying, or wait about 4 minutes for it to lapse and try again.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Already paid in cash", value = """
                                            {
                                              "code": "ORDER_ALREADY_PAID",
                                              "message": "This order was already paid in cash — do not charge the card.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Expired", value = """
                                            {
                                              "code": "ORDER_EXPIRED",
                                              "message": "This purchase order has expired — create a new one and take payment again.",
                                              "data": null
                                            }
                                            """)})),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "The order's currency is not one the card machines take",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "ZAR order", value = """
                                    {
                                      "code": "CARD_CURRENCY_UNSUPPORTED",
                                      "message": "The card machine can't take payment in ZAR. Take cash, or create the order in USD or ZWG.",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Shop staff (a cashier or shop admin) confirming a card-machine payment on a voucher "
                            + "to, from or paid by their own phone, or for a recipient on the merchant's staff "
                            + "list; or a caller outside this merchant",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Shop staff on their own voucher", value = """
                                            {
                                              "code": "SELF_CONFIRM",
                                              "message": "You can't confirm a cash or card payment for a voucher to or from your own phone. Pay by EcoCash or InnBucks, or ask a merchant admin to confirm it.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Recipient is a staff member", value = """
                                            {
                                              "code": "STAFF_RECIPIENT",
                                              "message": "This voucher is for a staff member, so shop staff can't confirm a cash or card payment for it. Pay by EcoCash or InnBucks, or ask a merchant admin to confirm it.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Another merchant", value = """
                                            {
                                              "code": "NOT_MERCHANT_OWNER",
                                              "message": "You can only act on merchants you administer.",
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
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SHOP_USER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.VoucherPurchaseOrderResponse>> confirmCard(
            @PathVariable String orderRef,
            @Valid @RequestBody Dtos.ConfirmCardPaymentRequest req) {
        return ResponseEntity.ok(ApiResult.ok("Card payment confirmed — voucher issued",
                purchases.confirmCardPos(tenantContext.requireTenantId(), orderRef,
                        req.approvalCode(), req.last4())));
    }
}
