package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.dto.SupportDtos;
import com.innbucks.loyaltyservice.security.SupportAgent;
import com.innbucks.loyaltyservice.security.SupportPermissions;
import com.innbucks.loyaltyservice.service.SupportCustomerService;
import com.innbucks.loyaltyservice.service.SupportNoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.BAD_PAGE_PARAM;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.CUSTOMER_360;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.FORBIDDEN;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.LOOKUP_NOT_FOUND;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.UNAUTHORIZED;

/**
 * Customer support — find a customer and read their record, across every tenant.
 *
 * <p><b>Permission-gated, never role-gated.</b> Every handler is
 * {@code hasAuthority('<permission>')} from the JWT {@code perms} claim; a
 * SUPER_ADMIN reaches it through the permissions its {@code *} wildcard expands
 * to at mint time, not through its role. No {@code X-Tenant-Id}: support is a
 * platform function.
 *
 * <p><b>Phones stay out of URLs.</b> The phone is typed once, into the body of
 * {@code POST /customers/lookup}; everything after that is addressed by the
 * {@code lookupId} it returns, which resolves to its phone only for the agent who
 * made it and only for {@code loyalty.support.lookup-ttl}. Every read writes a
 * {@code support_activity} row. Every response masks phones and carries no
 * voucher code.
 *
 * <p>Not an internal surface: it rides the gateway's existing {@code /loyalty/**}
 * route and is authenticated like any other staff endpoint.
 */
@RestController
@RequestMapping("/loyalty/support")
@Tag(name = "Customer Support",
        description = "Call-centre lookups across every tenant. Gated by the JWT `perms` claim "
                + "(loyalty-support:read / :manage). No X-Tenant-Id. Phones are masked in every response "
                + "and no voucher code is ever returned.")
public class SupportCustomerController {

    private final SupportCustomerService customers;
    private final SupportNoteService notes;

    public SupportCustomerController(SupportCustomerService customers, SupportNoteService notes) {
        this.customers = customers;
        this.notes = notes;
    }

    @PostMapping("/customers/lookup")
    @PreAuthorize(SupportPermissions.HAS_READ)
    @Operation(summary = "Look a customer up by phone (starts a lookup session)",
            description = """
                    Normalises the phone and checks whether it is ON RECORD anywhere loyalty keeps one: a \
                    registration (live or revoked), a membership in any tenant, a wallet, a voucher (held, \
                    sent or transferred away) or a voucher purchase order (payer, recipient or sender).

                    Found: writes a CUSTOMER_LOOKUP activity row and returns its id as `lookupId`, with \
                    the customer 360. Carry `lookupId` on every drill-down; it is yours alone and expires \
                    at `expiresAt`.

                    Not found: 404 `customer_not_found`, and a SEARCH activity row recording the MASKED \
                    phone — a lookup that finds nobody is still a lookup somebody made.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Customer found; lookup session started",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Found", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Customer found",
                                      "data": {
                                    """ + CUSTOMER_360 + """
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Blank body field, or a phone that does not parse",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Not a phone number", value = """
                                            {
                                              "code": "invalid_msisdn",
                                              "message": "That is not a valid phone number.",
                                              "data": null
                                            }"""),
                                    @ExampleObject(name = "Blank phone", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Validation failed",
                                              "data": { "phone": "must not be blank" }
                                            }""")})),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:read",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No loyalty customer on record for that phone",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "customer_not_found",
                                      "message": "No loyalty customer is on record for that phone number.",
                                      "data": null
                                    }""")))
    })
    public ResponseEntity<ApiResult<SupportDtos.LookupResponse>> lookup(
            @Valid @RequestBody SupportDtos.LookupRequest body) {
        SupportDtos.LookupResponse data = customers.lookup(SupportAgent.current(), body.phone());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.created("Customer found", data));
    }

    @GetMapping("/lookups/{lookupId}")
    @PreAuthorize(SupportPermissions.HAS_READ)
    @Operation(summary = "The customer 360 again, for a live lookup",
            description = "Same shape as the lookup response. Writes a VIEW_CUSTOMER activity row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The 360",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "OK",
                                      "data": {
                                    """ + CUSTOMER_360 + """
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:read",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Expired, not yours, or never a lookup — one answer for all three",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<SupportDtos.LookupResponse>> view(@PathVariable UUID lookupId) {
        return noStore(ApiResult.ok(customers.view(SupportAgent.current(), lookupId)));
    }

    @GetMapping("/lookups/{lookupId}/transactions")
    @PreAuthorize(SupportPermissions.HAS_READ)
    @Operation(summary = "The customer's points statement across every tenant, newest first",
            description = "Every loyalty transaction on any of the phone's memberships. Page size is capped "
                    + "at 100. Writes a VIEW_TRANSACTIONS activity row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of transactions",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "OK",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "33333333-4444-5555-6666-777777777777",
                                            "tenantId": "0a571c1c-7c75-4000-a000-000000000001",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "merchantName": "Example Pizza",
                                            "userId": "8f14e45f-ceea-467a-9ba6-7c3f0e2a1b44",
                                            "type": "ADJUSTMENT",
                                            "status": "POSTED",
                                            "amount": null,
                                            "currency": "USD",
                                            "pointsDelta": 250.0000,
                                            "reference": "Goodwill credit",
                                            "reversesId": null,
                                            "shopId": null,
                                            "postedBy": "5b0e7a1c-3f2d-4c9e-8a7b-6d5e4f3a2b1c",
                                            "channel": null,
                                            "createdAt": "2026-09-30T08:15:00Z"
                                          }
                                        ],
                                        "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
                                        "first": true, "last": true
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "A page/size that is not a number",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = BAD_PAGE_PARAM))),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:read",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Lookup expired or not yours",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<PageResponse<SupportDtos.TransactionLine>>> transactions(
            @PathVariable UUID lookupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(ApiResult.ok(customers.transactions(SupportAgent.current(), lookupId, page, size)));
    }

    @GetMapping("/lookups/{lookupId}/ledger")
    @PreAuthorize(SupportPermissions.HAS_READ)
    @Operation(summary = "The points ledger across the customer's wallets, newest first",
            description = "The append-only balance history (points_ledger) — including movements with no "
                    + "loyalty transaction behind them, such as expiry. Writes a VIEW_LEDGER activity row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of ledger entries",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "OK",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "44444444-5555-6666-7777-888888888888",
                                            "walletId": "3d1f0c2b-8a9e-4b7c-9d6e-5f4a3b2c1d0e",
                                            "tenantId": "0a571c1c-7c75-4000-a000-000000000001",
                                            "transactionId": "33333333-4444-5555-6666-777777777777",
                                            "delta": 250.0000,
                                            "balanceAfter": 1250.0000,
                                            "reason": "adjust:Goodwill credit",
                                            "createdAt": "2026-09-30T08:15:00Z"
                                          }
                                        ],
                                        "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
                                        "first": true, "last": true
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "A page/size that is not a number",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = BAD_PAGE_PARAM))),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:read",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Lookup expired or not yours",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<PageResponse<SupportDtos.LedgerLine>>> ledger(
            @PathVariable UUID lookupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(ApiResult.ok(customers.ledger(SupportAgent.current(), lookupId, page, size)));
    }

    @GetMapping("/lookups/{lookupId}/vouchers")
    @PreAuthorize(SupportPermissions.HAS_READ)
    @Operation(summary = "The customer's vouchers in one role — WITHOUT codes",
            description = """
                    role=HELD (default): vouchers this phone holds — by the assignee phone, else the \
                    assigned membership, the same precedence redemption applies. role=SENT: vouchers it \
                    gifted. role=TRANSFERRED: vouchers it transferred away. Newest first.

                    A voucher code is a bearer credential and is never returned here; use the resend \
                    action to get it to the holder's own phone. Writes a VIEW_VOUCHERS activity row.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of vouchers",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "OK",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "6a5b4c3d-2e1f-4a0b-9c8d-7e6f5a4b3c2d",
                                            "tenantId": "0a571c1c-7c75-4000-a000-000000000001",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "merchantName": "Example Pizza",
                                            "status": "ISSUED",
                                            "voucherType": "SINGLE_USE",
                                            "value": 5.0000,
                                            "currency": "USD",
                                            "holder": "****4567",
                                            "holderName": "Rudo",
                                            "sender": "****8899",
                                            "senderName": "Tatenda",
                                            "transferredFrom": null,
                                            "usesRemaining": 1,
                                            "issuedAt": "2026-09-20T10:00:00Z",
                                            "deliveredAt": "2026-09-20T10:00:01Z",
                                            "viewedAt": null,
                                            "redeemedAt": null,
                                            "expiresAt": "2027-09-20T10:00:00Z",
                                            "transferredAt": null,
                                            "campaignSource": null
                                          }
                                        ],
                                        "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
                                        "first": true, "last": true
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "An unknown role, or a page/size that is not a number",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "400 BAD_REQUEST",
                              "message": "Invalid value for 'role'. Accepted values: HELD, SENT, TRANSFERRED.",
                              "data": null
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:read",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Lookup expired or not yours",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<PageResponse<SupportDtos.VoucherLine>>> vouchers(
            @PathVariable UUID lookupId,
            @Parameter(description = "HELD (default), SENT or TRANSFERRED")
            @RequestParam(required = false) SupportCustomerService.VoucherRole role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(ApiResult.ok(customers.vouchers(SupportAgent.current(), lookupId, role, page, size)));
    }

    @GetMapping("/lookups/{lookupId}/voucher-orders")
    @PreAuthorize(SupportPermissions.HAS_READ)
    @Operation(summary = "Voucher purchase orders the customer appears on, in any role",
            description = "As payer, recipient or sender; `roles` names which. Newest first. Writes a "
                    + "VIEW_VOUCHER_ORDERS activity row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of orders",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "OK",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b",
                                            "orderRef": "VCH-1A2B3C4D5E6F",
                                            "roles": ["PAYER", "SENDER"],
                                            "tenantId": "0a571c1c-7c75-4000-a000-000000000001",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "merchantName": "Example Pizza",
                                            "status": "PAID",
                                            "amount": 5.0000,
                                            "currency": "USD",
                                            "payer": "****4567",
                                            "recipient": "****7788",
                                            "sender": "****4567",
                                            "paidVia": "GATEWAY",
                                            "paidAt": "2026-09-21T08:15:00Z",
                                            "expiresAt": "2026-09-21T08:40:00Z",
                                            "createdAt": "2026-09-21T08:10:00Z",
                                            "voucherId": "1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b"
                                          }
                                        ],
                                        "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
                                        "first": true, "last": true
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "A page/size that is not a number",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = BAD_PAGE_PARAM))),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:read",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Lookup expired or not yours",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<PageResponse<SupportDtos.OrderLine>>> voucherOrders(
            @PathVariable UUID lookupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(ApiResult.ok(customers.voucherOrders(SupportAgent.current(), lookupId, page, size)));
    }

    @GetMapping("/lookups/{lookupId}/notes")
    @PreAuthorize(SupportPermissions.HAS_READ)
    @Operation(summary = "Internal notes on the customer, newest first",
            description = "Every agent's notes on this phone. Writes a VIEW_NOTES activity row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of notes",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "OK",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "7c6b5a4f-3e2d-4c1b-9a0f-8e7d6c5b4a3f",
                                            "subjectKind": "PHONE",
                                            "subjectId": "****4567",
                                            "body": "Customer called about a voucher that did not arrive. Resent via WhatsApp.",
                                            "createdBy": {
                                              "uuid": "5b0e7a1c-3f2d-4c9e-8a7b-6d5e4f3a2b1c",
                                              "login": "agent.moyo@example.com"
                                            },
                                            "createdAt": "2026-09-30T08:20:00Z"
                                          }
                                        ],
                                        "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
                                        "first": true, "last": true
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "A page/size that is not a number",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = BAD_PAGE_PARAM))),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:read",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Lookup expired or not yours",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<PageResponse<SupportDtos.NoteResponse>>> notes(
            @PathVariable UUID lookupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(ApiResult.ok(notes.list(SupportAgent.current(), lookupId, page, size)));
    }

    @PostMapping("/lookups/{lookupId}/notes")
    @PreAuthorize(SupportPermissions.HAS_MANAGE)
    @Operation(summary = "Add an internal note on the customer",
            description = "Append-only: there is no edit and no delete. The body is HTML-stripped and trimmed "
                    + "and must then be 1..2000 characters. Writes a NOTE_ADDED activity row.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Note added",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Note added",
                                      "data": {
                                        "id": "7c6b5a4f-3e2d-4c1b-9a0f-8e7d6c5b4a3f",
                                        "subjectKind": "PHONE",
                                        "subjectId": "****4567",
                                        "body": "Customer called about a voucher that did not arrive. Resent via WhatsApp.",
                                        "createdBy": {
                                          "uuid": "5b0e7a1c-3f2d-4c9e-8a7b-6d5e4f3a2b1c",
                                          "login": "agent.moyo@example.com"
                                        },
                                        "createdAt": "2026-09-30T08:20:00Z"
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Empty (after stripping) or longer than 2000 characters",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "invalid_note_body",
                              "message": "A note must be 1 to 2000 characters once formatting is removed.",
                              "data": null
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:manage",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Lookup expired or not yours",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<SupportDtos.NoteResponse>> addNote(
            @PathVariable UUID lookupId, @Valid @RequestBody SupportDtos.NoteRequest body) {
        SupportDtos.NoteResponse note = notes.add(SupportAgent.current(), lookupId, body.body());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResult.created("Note added", note));
    }

    /** Customer PII: no cache anywhere may keep a copy. */
    static <T> ResponseEntity<ApiResult<T>> noStore(ApiResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
