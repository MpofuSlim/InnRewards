package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.VoucherService;
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
     description = "Voucher lifecycle — issued directly with a money value " +
                   "and a currency; templates are retired (V45). Expiry comes from the tenant/merchant " +
                   "loyalty rules (voucherValidityDays). Each voucher carries an HMAC-SHA256 " +
                   "signature over its code (signed with `loyalty.voucher.secret`) so redemption can be " +
                   "verified offline if needed. Anti-fraud (duplicate, wrong-merchant, blocked-user, " +
                   "blocked-device, velocity) is enforced on every `/redeem`. Requires X-Tenant-Id.")
public class VoucherController {

    private final VoucherService voucherService;
    private final TenantContext tenantContext;

    public VoucherController(VoucherService voucherService,
                             TenantContext tenantContext) {
        this.voucherService = voucherService;
        this.tenantContext = tenantContext;
    }

    @PostMapping("/issue")
    @Operation(summary = "Issue a single voucher",
            description = "Mints one voucher directly — templates are retired (V45). The body carries the " +
                          "type (SINGLE_USE — the only issuable one), the money face value " +
                          "and an optional currency (defaults to the merchant's; allowlist-validated, fail " +
                          "closed, and a non-USD currency needs an in-force exchange rate). Expiry is NOT a " +
                          "request field: it resolves from the loyalty rules — the merchant's own rule's " +
                          "voucherValidityDays, else the tenant's global rule, else the platform default. " +
                          "The caller must administer the issuing merchant (SUPER_ADMIN exempt; SHOP_ADMIN " +
                          "is pinned to the merchant in their JWT). Optionally assign it to a known " +
                          "LoyaltyUser (`assignedUserId`) or to an arbitrary phone (`assigneePhone`). " +
                          "Optionally name the SENDER (`senderName` + `senderPhone`, V46): the recipient's " +
                          "message then reads as a personal gift (\"Tawanda Mpofu sent you an InnBucks " +
                          "voucher\") and the sender's phone gets its own WhatsApp/SMS confirmation copy — " +
                          "`senderPhone` has NO default: omit it and the voucher simply has no sender and " +
                          "no confirmation copy is sent. It is never taken from the caller's own token, so " +
                          "at a till the cashier must type the CUSTOMER's number as the sender; the " +
                          "cashier is recorded separately as the issuer. " +
                          "Returns the signed voucher code the customer presents at redemption. " +
                          "DELIVERY IS ALWAYS WhatsApp first with an SMS fallback, to the voucher's " +
                          "holder — `deliveryChannel` is legacy and selects nothing (it never did); omit " +
                          "it. Send `NONE` only to suppress the send entirely.")
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
                                        "code": "4829137605128368",
                                        "status": "ISSUED",
                                        "voucherType": "SINGLE_USE",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "shopId": null,
                                        "batchId": null,
                                        "campaignSource": null,
                                        "assignedUserId": "d2c8f0a1-0123-4567-1234-567890123456",
                                        "assigneePhone": "+263771234567",
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
                                        "issuedAt": "2026-09-17T10:30:00Z",
                                        "deliveredAt": "2026-09-17T10:30:05Z",
                                        "viewedAt": null,
                                        "redeemedAt": null,
                                        "transferredAt": null,
                                        "expiresAt": "2026-10-17T10:30:00Z",
                                        "transferredFromUserId": null,
                                        "transferredFromPhone": null
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error — missing/non-positive value, an unsupported currency, "
                            + "a currency with no in-force exchange rate, a `usageLimit` other than 1, or "
                            + "`voucherType: MULTI_USE`, which is retired (`MULTI_USE_RETIRED`)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Missing value", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Validation failed",
                                              "data": { "value": "must not be null" }
                                            }
                                            """),
                                    @ExampleObject(name = "Unsupported currency", value = """
                                            {
                                              "code": "UNSUPPORTED_CURRENCY",
                                              "message": "Currency GBP is not supported on this cell. Supported: USD, ZAR, ZWG.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "MULTI_USE is retired", value = """
                                            {
                                              "code": "MULTI_USE_RETIRED",
                                              "message": "Multi-use vouchers are no longer issued — a voucher is worth its face value and is redeemed once. Issue it as SINGLE_USE, or issue several vouchers.",
                                              "data": null
                                            }
                                            """)}
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Caller does not administer the issuing merchant "
                            + "(`NOT_MERCHANT_OWNER`), or the named `assignedUserId` belongs to "
                            + "another tenant (`CROSS_TENANT`). Branch on `code` — a "
                            + "LoyaltyException keeps its domain code in the envelope.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not merchant owner", value = """
                                    {
                                      "code": "NOT_MERCHANT_OWNER",
                                      "message": "You can only act on merchants you administer.",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Merchant not found in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "merchant not found",
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
    @Operation(summary = "Bulk-issue vouchers",
            description = "Mints `quantity` independent unassigned vouchers in one call — same direct shape " +
                          "as /issue (type + money value + currency; expiry from the loyalty rules), applied " +
                          "to every voucher in the batch. Each gets its own unique signed code. The caller " +
                          "must administer the issuing merchant.")
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
                                          "code": "4829137605128368",
                                          "status": "ISSUED",
                                          "voucherType": "SINGLE_USE",
                                          "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "shopId": null,
                                          "batchId": "9f9f9f9f-0000-1111-2222-333333333333",
                                          "campaignSource": "spring-2026",
                                          "assignedUserId": null,
                                          "assigneePhone": null,
                                          "assigneeName": null,
                                          "senderName": null,
                                          "senderPhone": null,
                                          "issuerUserId": "77777777-7777-7777-7777-777777777777",
                                          "issuerPhone": "+263772000111",
                                          "issuerEmail": "shopadmin@westgate.co.zw",
                                          "usesRemaining": 1,
                                          "value": 5.0000,
                                          "currency": "USD",
                                          "baseValue": 5.0000,
                                          "issuedAt": "2026-05-04T10:30:00Z",
                                          "deliveredAt": null,
                                          "viewedAt": null,
                                          "redeemedAt": null,
                                          "transferredAt": null,
                                          "expiresAt": "2026-06-03T10:30:00Z",
                                          "transferredFromUserId": null,
                                          "transferredFromPhone": null
                                        },
                                        {
                                          "id": "d2c8f0a1-0123-4567-1234-567890123456",
                                          "code": "7183502649174053",
                                          "status": "ISSUED",
                                          "voucherType": "SINGLE_USE",
                                          "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "shopId": null,
                                          "batchId": "9f9f9f9f-0000-1111-2222-333333333333",
                                          "campaignSource": "spring-2026",
                                          "assignedUserId": null,
                                          "assigneePhone": null,
                                          "assigneeName": null,
                                          "senderName": null,
                                          "senderPhone": null,
                                          "issuerUserId": "77777777-7777-7777-7777-777777777777",
                                          "issuerPhone": "+263772000111",
                                          "issuerEmail": "shopadmin@westgate.co.zw",
                                          "usesRemaining": 1,
                                          "value": 5.0000,
                                          "currency": "USD",
                                          "baseValue": 5.0000,
                                          "issuedAt": "2026-05-04T10:30:00Z",
                                          "deliveredAt": null,
                                          "viewedAt": null,
                                          "redeemedAt": null,
                                          "transferredAt": null,
                                          "expiresAt": "2026-06-03T10:30:00Z",
                                          "transferredFromUserId": null,
                                          "transferredFromPhone": null
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
                                      "message": "Validation failed",
                                      "data": { "quantity": "must be greater than or equal to 1" }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Merchant not found in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "merchant not found",
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
                          "Failed attempts are recorded in `fraud_attempts`. When the velocity threshold is " +
                          "exceeded FraudService may auto-block the ACCOUNT OF THE AUTHENTICATED CALLER — " +
                          "never the `userId` sent in the body, which is recorded as a claim only. A " +
                          "staff-operated or service-to-service call blocks nobody: the velocity signal is " +
                          "keyed by device, and at a till the device is the shop's while the person " +
                          "presenting codes is a customer.")
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
                                        "value": 5.0000,
                                        "redeemedAt": "2026-05-04T14:00:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error, or EXPIRED — past the voucher's expiresAt. Bean "
                            + "validation always answers `Validation failed` with the offending fields "
                            + "in `data`; the field name is never in `message`. On EXPIRED the voucher "
                            + "is also moved to the EXPIRED status, and that lands BEFORE this response "
                            + "is written: the flip runs in a post-rollback listener inside the same "
                            + "server-side call, so a client that re-reads the voucher on receiving this "
                            + "error already sees EXPIRED. The flip is also idempotent — it only ever "
                            + "moves a still-live voucher whose deadline has genuinely passed — so a "
                            + "concurrent redemption or revocation is never overwritten.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Validation error", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Validation failed",
                                              "data": { "code": "must not be blank" }
                                            }
                                            """),
                                    @ExampleObject(name = "Expired", value = """
                                            {
                                              "code": "EXPIRED",
                                              "message": "This voucher has expired.",
                                              "data": null
                                            }
                                            """)}
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
                                      "message": "voucher not found",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = """
                            Rejected. Branch on `code`; `message` is customer-safe prose a cashier can \
                            read aloud.
                            * `BAD_SIGNATURE` — tampered code.
                            * `WRONG_MERCHANT` — not valid at this merchant.
                            * `NOT_VOUCHER_OWNER` — a customer bearer redeeming a voucher that is not \
                            theirs. The most likely 403 an app sees, and never returned to a staff \
                            caller, who presents the code on the holder's behalf.
                            * `NOT_MERCHANT_OWNER` — the caller does not administer the merchant it \
                            named. Only reachable for a caller whose token carries no `merchantId` \
                            claim; a claim-pinned caller cannot name another merchant at all.
                            * `USER_BLOCKED` / `USER_PENDING` / `USER_INACTIVE` — the state of the \
                            voucher HOLDER's account, resolved from the voucher. Sending `userId` \
                            cannot relax these and omitting it cannot skip them.""",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Wrong merchant", value = """
                                    {
                                      "code": "WRONG_MERCHANT",
                                      "message": "This voucher can't be redeemed at this shop.",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "REVOKED (cancelled by an operator — checked FIRST, so a voucher revoked "
                            + "after its last use reports REVOKED rather than ALREADY_REDEEMED) or "
                            + "ALREADY_REDEEMED (the voucher's single use is spent — a voucher is worth "
                            + "its face value once).",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Already redeemed", value = """
                                    {
                                      "code": "ALREADY_REDEEMED",
                                      "message": "This voucher has already been fully redeemed.",
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

    @PostMapping("/{id}/transfer")
    @Operation(summary = "Transfer a voucher to another customer (ONE hop only)",
            description = """
                    Hands the voucher to another customer. The recipient becomes the new assignee and \
                    redeems it as their own.

                    **A voucher can only be transferred ONCE.** The lifecycle is issued → transferred → \
                    redeemed; a voucher that has already changed hands is refused with \
                    `VOUCHER_ALREADY_TRANSFERRED`. This keeps a voucher from becoming a bearer instrument \
                    that circulates and can be sold on — the merchant is billed for issuing it and needs \
                    the link between who was given the incentive and who redeems it to survive.

                    Only an **unused, live** voucher moves: `ISSUED` or `VIEWED`. \
                    `PARTIALLY_USED` is refused along with the terminal states — the original holder has \
                    already consumed part of the value, and splitting the rest across two people makes the \
                    redemption trail ambiguous. An expired voucher is refused too.

                    The **caller must be the current holder** (a CUSTOMER whose JWT phone matches the \
                    assignee). Staff roles may transfer on a customer's behalf for support.

                    Send exactly one of `toUserId` or `toPhone`. An unknown `toPhone` is auto-enrolled as a \
                    PENDING loyalty user, so you can pass a voucher to someone who hasn't signed up yet — \
                    it becomes redeemable once they register.

                    The pre-expiry warning is reset on transfer so the NEW holder gets their own warning; \
                    the delivered/viewed timestamps are left as history.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Voucher transferred — the response shows the NEW assignee. `code` is "
                            + "ALWAYS null: the transfer rotates the voucher's code and deliberately "
                            + "withholds it from the caller (the sender), or the rotation would be "
                            + "pointless. The recipient reads the new code in-app as the voucher's new "
                            + "assignee, and receives it by WhatsApp/SMS.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Transferred", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Voucher transferred successfully",
                                      "data": {
                                        "id": "9f8e7d6c-5b4a-3210-fedc-ba9876543210",
                                        "code": null,
                                        "status": "VIEWED",
                                        "voucherType": "SINGLE_USE",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "shopId": null,
                                        "batchId": null,
                                        "campaignSource": null,
                                        "assignedUserId": "66666666-7777-8888-9999-000000000000",
                                        "assigneePhone": "+263771234567",
                                        "assigneeName": "Sedrick Nyanyiwa",
                                        "senderName": "Tawanda Mpofu",
                                        "senderPhone": "+263782608767",
                                        "issuerUserId": "77777777-7777-7777-7777-777777777777",
                                        "issuerPhone": "+263772000111",
                                        "issuerEmail": "shopadmin@westgate.co.zw",
                                        "usesRemaining": 1,
                                        "value": 10.0000,
                                        "currency": "USD",
                                        "baseValue": 10.0000,
                                        "issuedAt": "2026-08-20T09:00:00Z",
                                        "deliveredAt": "2026-08-20T09:00:05Z",
                                        "viewedAt": "2026-08-21T09:00:00Z",
                                        "redeemedAt": null,
                                        "transferredAt": "2026-08-20T10:00:00Z",
                                        "expiresAt": "2027-08-20T09:00:00Z",
                                        "transferredFromUserId": "44444444-4444-4444-4444-444444444444",
                                        "transferredFromPhone": "+263779999888"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Already transferred (`VOUCHER_ALREADY_TRANSFERRED`), not in a "
                            + "transferable state (`VOUCHER_NOT_TRANSFERABLE`), expired "
                            + "(`VOUCHER_EXPIRED`), sending to yourself (`SELF_TRANSFER`), or neither / "
                            + "both of toUserId and toPhone supplied (`RECIPIENT_REQUIRED`).",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Already transferred once", value = """
                                            {
                                              "code": "VOUCHER_ALREADY_TRANSFERRED",
                                              "message": "This voucher has already been transferred once and can't be passed on again.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Not transferable", value = """
                                            {
                                              "code": "VOUCHER_NOT_TRANSFERABLE",
                                              "message": "Only an unused voucher can be transferred (this one is REDEEMED).",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Recipient missing or ambiguous", value = """
                                            {
                                              "code": "RECIPIENT_REQUIRED",
                                              "message": "supply exactly one of toUserId or toPhone",
                                              "data": null
                                            }
                                            """)
                            }
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Caller is not the voucher's current holder (`NOT_VOUCHER_OWNER`) or the "
                            + "voucher belongs to another tenant (`CROSS_TENANT`).",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not the holder", value = """
                                    {
                                      "code": "NOT_VOUCHER_OWNER",
                                      "message": "you can only act on your own vouchers",
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
                                      "message": "voucher not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('CUSTOMER','SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.VoucherResponse>> transfer(
            @PathVariable UUID id,
            @Valid @RequestBody Dtos.VoucherTransferRequest req) {
        Dtos.VoucherResponse data = voucherService.transfer(tenantContext.requireTenantId(), id, req);
        return ResponseEntity.ok(ApiResult.ok("Voucher transferred successfully", data));
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "Revoke an issued voucher",
            description = "Marks the voucher REVOKED so it can no longer be redeemed. Use for fraud, " +
                          "support refunds, or when a customer reports their code stolen. Revoke does NOT " +
                          "inspect the current status — an already-redeemed voucher is simply set to " +
                          "REVOKED.")
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
                                      "message": "voucher not found",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "The voucher belongs to another merchant (WRONG_MERCHANT) or another tenant "
                            + "(CROSS_TENANT). A merchant-scoped caller may only revoke its own merchant's "
                            + "vouchers. Note revoke does NOT reject an already-redeemed voucher — it simply "
                            + "sets the status to REVOKED.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Wrong merchant", value = """
                                    {
                                      "code": "WRONG_MERCHANT",
                                      "message": "This voucher belongs to a different merchant.",
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
                          "since the code itself identifies the tenant. An unrecognised code is a no-op 200, " +
                          "not a 404.")
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
                    responseCode = "403",
                    description = "The caller is neither the voucher's holder nor merchant/issuing staff. "
                            + "There is deliberately NO 404: an unknown code is a silent 200, because this "
                            + "is a best-effort read receipt (VoucherService.markViewed uses "
                            + "findByCode().ifPresent).",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not the holder", value = """
                                    {
                                      "code": "NOT_VOUCHER_OWNER",
                                      "message": "you can only act on your own vouchers",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('CUSTOMER','SHOP_USER','SHOP_ADMIN','MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Void>> markViewed(
            @io.swagger.v3.oas.annotations.Parameter(example = "4829137605128368",
                    description = "The voucher code — send the raw value from the API. Grouped input is "
                            + "accepted if its spaces are URL-encoded as %20 (a '+' in a path is a literal "
                            + "plus, not a space, and will not match).")
            @PathVariable String code) {
        voucherService.markViewed(code);
        return ResponseEntity.ok(ApiResult.ok("Voucher view recorded", null));
    }

    @GetMapping("/users/by-phone/{phoneNumber}/active")
    @Operation(summary = "List a phone's active vouchers in the caller's tenant",
            description = "Returns every voucher in an active state (ISSUED, VIEWED, " +
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
                                            "code": "4829137605128368",
                                            "status": "ISSUED",
                                            "voucherType": "SINGLE_USE",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "shopId": null,
                                            "batchId": null,
                                            "campaignSource": null,
                                            "assignedUserId": "d2c8f0a1-0123-4567-1234-567890123456",
                                            "assigneePhone": "+263771234567",
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
                                            "issuedAt": "2026-05-04T10:30:00Z",
                                            "deliveredAt": "2026-05-04T10:30:05Z",
                                            "viewedAt": null,
                                            "redeemedAt": null,
                                            "transferredAt": null,
                                            "expiresAt": "2026-06-03T10:30:00Z",
                                            "transferredFromUserId": null,
                                            "transferredFromPhone": null
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
                                            "code": "4829137605128368",
                                            "status": "ISSUED",
                                            "voucherType": "SINGLE_USE",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "shopId": null,
                                            "batchId": null,
                                            "campaignSource": null,
                                            "assignedUserId": "d2c8f0a1-0123-4567-1234-567890123456",
                                            "assigneePhone": "+254700000000",
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
                                            "issuedAt": "2026-05-04T10:30:00Z",
                                            "deliveredAt": "2026-05-04T10:30:05Z",
                                            "viewedAt": null,
                                            "redeemedAt": null,
                                            "transferredAt": null,
                                            "expiresAt": "2026-06-03T10:30:00Z",
                                            "transferredFromUserId": null,
                                            "transferredFromPhone": null
                                          },
                                          {
                                            "id": "f4eab2c3-2345-6789-3456-789012345678",
                                            "code": "5630219487361501",
                                            "status": "ISSUED",
                                            "voucherType": "SINGLE_USE",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "shopId": null,
                                            "batchId": null,
                                            "campaignSource": null,
                                            "assignedUserId": null,
                                            "assigneePhone": "+254711111111",
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
                                            "issuedAt": "2026-05-03T14:00:00Z",
                                            "deliveredAt": "2026-05-03T14:00:05Z",
                                            "viewedAt": null,
                                            "redeemedAt": null,
                                            "transferredAt": null,
                                            "expiresAt": "2026-06-02T14:00:00Z",
                                            "transferredFromUserId": null,
                                            "transferredFromPhone": null
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
                    description = """
                            Unknown status value. The retired `DELIVERED` is still \
                            ACCEPTED here and binds to `ISSUED` (V48 merged the two), \
                            so an operator console still sending the old filter keeps \
                            working — but the service never RETURNS `DELIVERED`.""",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Bad status", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Invalid value for 'status'. Accepted values: ISSUED, VIEWED, REDEEMED, PARTIALLY_USED, EXPIRED, REVOKED.",
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
