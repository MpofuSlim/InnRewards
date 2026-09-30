package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.SupportDtos;
import com.innbucks.loyaltyservice.security.SupportAgent;
import com.innbucks.loyaltyservice.security.SupportPermissions;
import com.innbucks.loyaltyservice.service.SupportActionService;
import com.innbucks.loyaltyservice.service.SupportMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.AGENT_IDENTITY_REQUIRED;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.CHANNEL_UNAVAILABLE;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.FORBIDDEN;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.INVALID_REASON;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.LOOKUP_NOT_FOUND;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.MEMBERSHIP_NOT_FOUND;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.NOT_DELIVERED;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.RATE_LIMITED;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.REASON_BLANK;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.UNAUTHORIZED;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.VOUCHER_RESENT;

/**
 * What an agent may DO for the looked-up customer. Agent actions need
 * {@code loyalty-support:manage}; moving points and lifting a fraud hold need
 * {@code loyalty-support:supervise}. Every target (voucher, membership,
 * transaction) must belong to the looked-up phone — anyone else's is a 404, the
 * same as one that does not exist — and every action reuses the service's
 * existing rules (adjustment ceilings, reversal guards, the BLOCKED-only
 * unblock). One activity row per action; a reason is kept where the action keeps
 * one, or as an internal note.
 */
@RestController
@RequestMapping("/loyalty/support/lookups/{lookupId}")
@Tag(name = "Customer Support — Actions",
        description = "Resend a voucher, sign the customer out (loyalty-support:manage); adjust or reverse "
                + "points, lift a fraud hold (loyalty-support:supervise). Bound to the looked-up customer.")
public class SupportActionController {

    private final SupportActionService actions;
    private final SupportMessageService messages;

    public SupportActionController(SupportActionService actions, SupportMessageService messages) {
        this.actions = actions;
        this.messages = messages;
    }

    @PostMapping("/vouchers/{voucherId}/resend")
    @PreAuthorize(SupportPermissions.HAS_MANAGE)
    @Operation(summary = "Resend a voucher to its holder",
            description = """
                    Sends the voucher's ISSUE message again — the same template the holder got, code \
                    included — to the looked-up customer's own phone, and only there. The voucher must be \
                    held by this customer (assignee phone, else assigned membership — the redemption rule) \
                    and still live.

                    Counts against the same message limits as a typed message. The code is never stored \
                    (the record's text is null) and never returned. Writes a MESSAGE_SENT activity row.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Sent to the holder",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = VOUCHER_RESENT))),
            @ApiResponse(responseCode = "400", description = "Missing channel",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "400 BAD_REQUEST",
                              "message": "Validation failed",
                              "data": { "channel": "must not be null" }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:manage",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Lookup expired or not yours, or the customer holds no such voucher",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Lookup", value = LOOKUP_NOT_FOUND),
                            @ExampleObject(name = "Voucher", value = """
                                    {
                                      "code": "voucher_not_found",
                                      "message": "This customer holds no voucher with that id.",
                                      "data": null
                                    }""")})),
            @ApiResponse(responseCode = "409", description = "Redeemed, revoked or expired — nothing worth resending",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "voucher_not_live",
                              "message": "This voucher can no longer be redeemed (REDEEMED), so there is nothing to resend.",
                              "data": null
                            }"""))),
            @ApiResponse(responseCode = "429", description = "Over a message limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = RATE_LIMITED))),
            @ApiResponse(responseCode = "502", description = "Every channel failed; the FAILED record is in data",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_DELIVERED))),
            @ApiResponse(responseCode = "503", description = "The channel is not provisioned on this cell (for SMS_THEN_WHATSAPP: neither is)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = CHANNEL_UNAVAILABLE)))
    })
    public ResponseEntity<ApiResult<SupportDtos.MessageResponse>> resendVoucher(
            @PathVariable UUID lookupId, @PathVariable UUID voucherId,
            @Valid @RequestBody SupportDtos.ResendRequest body) {
        SupportDtos.MessageResponse sent = messages.resendVoucher(SupportAgent.current(), lookupId, voucherId,
                body.channel());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResult.created("Voucher resent to its holder", sent));
    }

    @PostMapping("/sign-out")
    @PreAuthorize(SupportPermissions.HAS_MANAGE)
    @Operation(summary = "Sign the customer out of every loyalty session",
            description = """
                    Revokes every refresh-token chain of the looked-up phone, so no device can renew its \
                    session: the next renewal fails and the app asks the customer to prove their phone \
                    again. An access token ALREADY issued keeps working until it expires (at most 12h) — \
                    it carries no user id the fleet denylist could reach; what ends now is renewal.

                    The reason is recorded as an internal note on the customer. Writes a SESSIONS_REVOKED \
                    activity row naming the note.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed out",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Customer signed out of every loyalty session",
                                      "data": {
                                        "chainsRevoked": 2,
                                        "tokensRevoked": 2,
                                        "noteId": "7c6b5a4f-3e2d-4c1b-9a0f-8e7d6c5b4a3f"
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Missing, or empty once formatting is removed",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Blank", value = REASON_BLANK),
                            @ExampleObject(name = "Empty after stripping", value = INVALID_REASON)})),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:manage",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Lookup expired or not yours",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<SupportDtos.SignOutResponse>> signOut(
            @PathVariable UUID lookupId, @Valid @RequestBody SupportDtos.SignOutRequest body) {
        return SupportCustomerController.noStore(ApiResult.ok("Customer signed out of every loyalty session",
                actions.signOut(SupportAgent.current(), lookupId, body.reason())));
    }

    @PostMapping("/points/adjust")
    @PreAuthorize(SupportPermissions.HAS_SUPERVISE)
    @Operation(summary = "Credit or debit points on one of the customer's memberships",
            description = """
                    The merchant adjustment, bound to the looked-up customer: `userId` must be one of \
                    their memberships and `merchantId` a merchant in that membership's programme. The \
                    SAME ceilings apply — at most 5000 points per adjustment and 20000 per operator per \
                    rolling 24h (LOYALTY_MAX_PER_ADJUSTMENT / LOYALTY_MAX_DAILY_ADJUSTMENT_PER_OPERATOR), \
                    counted against YOU as the operator. A supervisor is not exempt; only SUPER_ADMIN is. \
                    The customer gets the usual adjustment SMS.

                    `reason` (1..96) is stored where every adjustment keeps it: the transaction \
                    reference and the ledger entry. Writes a POINTS_ADJUSTED activity row.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Adjustment applied",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Adjustment applied",
                                      "data": {
                                        "id": "33333333-4444-5555-6666-777777777777",
                                        "type": "ADJUSTMENT",
                                        "amount": null,
                                        "pointsDelta": 250,
                                        "balanceAfter": 1500.0000,
                                        "ruleId": null,
                                        "campaignId": null,
                                        "shopId": null,
                                        "postedBy": "5b0e7a1c-3f2d-4c9e-8a7b-6d5e4f3a2b1c",
                                        "channel": null,
                                        "reference": "Goodwill credit for failed voucher",
                                        "createdAt": "2026-09-30T08:15:00Z",
                                        "invoiceId": null,
                                        "currency": "USD",
                                        "baseAmount": null
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Zero, fractional or missing points; a missing or over-long reason",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Zero", value = """
                                    {
                                      "code": "invalid_points",
                                      "message": "points must be a whole number other than 0.",
                                      "data": null
                                    }"""),
                            @ExampleObject(name = "Reason too long", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": { "reason": "size must be between 0 and 96" }
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "No supervise permission, over a ceiling, or a token with no userUuid",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Per-adjustment ceiling", value = """
                                    {
                                      "code": "ADJUSTMENT_LIMIT_EXCEEDED",
                                      "message": "That adjustment is larger than you're allowed to post. Ask a super admin to make it.",
                                      "data": null
                                    }"""),
                            @ExampleObject(name = "Daily ceiling", value = """
                                    {
                                      "code": "ADJUSTMENT_DAILY_LIMIT_EXCEEDED",
                                      "message": "You've reached your daily adjustment limit. Ask a super admin to make this one.",
                                      "data": null
                                    }"""),
                            @ExampleObject(name = "No permission", value = FORBIDDEN),
                            @ExampleObject(name = "No userUuid", value = AGENT_IDENTITY_REQUIRED)})),
            @ApiResponse(responseCode = "404", description = "Lookup, membership or merchant not the customer's",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Membership", value = MEMBERSHIP_NOT_FOUND),
                            @ExampleObject(name = "Merchant", value = """
                                    {
                                      "code": "merchant_not_found",
                                      "message": "No such merchant in this membership's loyalty programme.",
                                      "data": null
                                    }"""),
                            @ExampleObject(name = "Lookup", value = LOOKUP_NOT_FOUND)}))
    })
    public ResponseEntity<ApiResult<Dtos.TransactionResponse>> adjust(
            @PathVariable UUID lookupId, @Valid @RequestBody SupportDtos.AdjustRequest body) {
        return SupportCustomerController.noStore(ApiResult.ok("Adjustment applied",
                actions.adjust(SupportAgent.current(), lookupId, body)));
    }

    @PostMapping("/transactions/{transactionId}/reverse")
    @PreAuthorize(SupportPermissions.HAS_SUPERVISE)
    @Operation(summary = "Reverse one of the customer's transactions",
            description = """
                    The merchant reversal, bound to the looked-up customer: the transaction must be on \
                    one of their memberships. Same row lock, same 409 ALREADY_REVERSED, and the \
                    compensation is what the original actually moved on the wallet. `reason` (1..192) is \
                    stored on the ledger entry. Writes a TRANSACTION_REVERSED activity row.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reversed",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Transaction reversed",
                                      "data": {
                                        "id": "55555555-6666-7777-8888-999999999999",
                                        "type": "ADJUSTMENT",
                                        "amount": 25.0000,
                                        "pointsDelta": -250.0000,
                                        "balanceAfter": 1000.0000,
                                        "ruleId": null,
                                        "campaignId": null,
                                        "shopId": "c7d8e9f0-1234-5678-90ab-cdef12345678",
                                        "postedBy": "5b0e7a1c-3f2d-4c9e-8a7b-6d5e4f3a2b1c",
                                        "channel": null,
                                        "reference": "REV-POS-20260929-0042",
                                        "createdAt": "2026-09-30T08:50:00Z",
                                        "invoiceId": null,
                                        "currency": "USD",
                                        "baseAmount": null
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Missing, over-long, or empty once formatting is removed",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Blank", value = REASON_BLANK),
                            @ExampleObject(name = "Empty after stripping", value = INVALID_REASON)})),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "No supervise permission, or a token with no userUuid",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No permission", value = FORBIDDEN),
                            @ExampleObject(name = "No userUuid", value = AGENT_IDENTITY_REQUIRED)})),
            @ApiResponse(responseCode = "404", description = "Lookup expired, or the transaction is not the customer's",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Transaction", value = """
                                    {
                                      "code": "transaction_not_found",
                                      "message": "This customer has no transaction with that id.",
                                      "data": null
                                    }"""),
                            @ExampleObject(name = "Lookup", value = LOOKUP_NOT_FOUND)})),
            @ApiResponse(responseCode = "409", description = "Already reversed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "ALREADY_REVERSED",
                              "message": "This transaction has already been reversed.",
                              "data": null
                            }""")))
    })
    public ResponseEntity<ApiResult<Dtos.TransactionResponse>> reverse(
            @PathVariable UUID lookupId, @PathVariable UUID transactionId,
            @Valid @RequestBody SupportDtos.ReverseRequest body) {
        return SupportCustomerController.noStore(ApiResult.ok("Transaction reversed",
                actions.reverse(SupportAgent.current(), lookupId, transactionId, body.reason())));
    }

    @PostMapping("/memberships/{userId}/unblock")
    @PreAuthorize(SupportPermissions.HAS_SUPERVISE)
    @Operation(summary = "Lift a fraud hold on one of the customer's memberships",
            description = """
                    BLOCKED -> ACTIVE on a membership of the looked-up customer, through the same rule as \
                    the merchant unblock: anything not BLOCKED (PENDING, INACTIVE) is refused 409 \
                    USER_NOT_BLOCKED rather than made active. The reason is recorded as an internal note on \
                    the customer. Writes a MEMBERSHIP_UNBLOCKED activity row naming the note.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Hold lifted",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Fraud hold lifted",
                                      "data": {
                                        "membership": {
                                          "tenantId": "0a571c1c-7c75-4000-a000-000000000001",
                                          "tenantName": "Example Retail Group",
                                          "userId": "8f14e45f-ceea-467a-9ba6-7c3f0e2a1b44",
                                          "status": "ACTIVE",
                                          "statusReason": null,
                                          "joinedAt": "2026-07-14T11:02:09Z"
                                        },
                                        "noteId": "8d7c6b5a-4f3e-4d2c-9b1a-0f9e8d7c6b5a"
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Missing, or empty once formatting is removed",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Blank", value = REASON_BLANK),
                            @ExampleObject(name = "Empty after stripping", value = INVALID_REASON)})),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:supervise",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Lookup expired, or the membership is not the customer's",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Membership", value = MEMBERSHIP_NOT_FOUND),
                            @ExampleObject(name = "Lookup", value = LOOKUP_NOT_FOUND)})),
            @ApiResponse(responseCode = "409", description = "The membership is not blocked",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "USER_NOT_BLOCKED",
                              "message": "This account is not blocked (status PENDING).",
                              "data": null
                            }""")))
    })
    public ResponseEntity<ApiResult<SupportDtos.UnblockResponse>> unblock(
            @PathVariable UUID lookupId, @PathVariable UUID userId,
            @Valid @RequestBody SupportDtos.UnblockRequest body) {
        return SupportCustomerController.noStore(ApiResult.ok("Fraud hold lifted",
                actions.unblock(SupportAgent.current(), lookupId, userId, body.reason())));
    }
}
