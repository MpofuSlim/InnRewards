package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.VoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Self-service endpoints for the authenticated caller. {@code /me} resolves
 * "who am I?" from the JWT's phoneNumber claim rather than a path variable —
 * the typical "logged-in user" use case the mini-app needs.
 *
 * <p>The wallet endpoint is the answer to "I just opened the app, what do I have?".
 * It collapses every LoyaltyUser projection matching the caller's phone — across
 * every tenant — into a single aggregate response: total points, total active
 * vouchers. From the customer's perspective there's one wallet, not a per-tenant
 * breakdown.
 */
@RestController
@RequestMapping("/loyalty/users/me")
@Slf4j
@Tag(name = "Me",
     description = "Self-service endpoints for the authenticated caller — what's in my wallet, " +
                   "what vouchers can I redeem, etc. Resolves identity from the JWT phoneNumber claim.")
@SecurityRequirement(name = "bearerAuth")
public class MeController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private static final String NO_PHONE_EXAMPLE = """
            {
              "code": "NO_PHONE_CLAIM",
              "message": "/me endpoints require a JWT with a phoneNumber claim",
              "data": null
            }""";

    private final LoyaltyUserRepository users;
    private final VoucherRepository vouchers;
    private final WalletRepository wallets;
    private final TransactionService transactionService;

    public MeController(LoyaltyUserRepository users,
                        VoucherRepository vouchers,
                        WalletRepository wallets,
                        TransactionService transactionService) {
        this.users = users;
        this.vouchers = vouchers;
        this.wallets = wallets;
        this.transactionService = transactionService;
    }

    @GetMapping("/wallet")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get the caller's total balance + voucher count across every tenant",
            description = "Aggregates every LoyaltyUser projection matching the caller's phone into a single " +
                          "wallet view — totalPoints is the sum of all points balances (including PENDING / " +
                          "INACTIVE accruals); totalVouchers is the count of voucher rows currently in any of " +
                          "ISSUED / VIEWED / PARTIALLY_USED states. " +
                          "Requires a JWT carrying a phoneNumber claim (CUSTOMER tokens always do)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Wallet retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Wallet retrieved",
                                      "data": {
                                        "phoneNumber": "+263771234567",
                                        "totalPoints": 225.00,
                                        "totalVouchers": 3
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "NO_PHONE_CLAIM — the JWT carries no phoneNumber",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "NO_PHONE_CLAIM",
                                      "message": "/me endpoints require a JWT with a phoneNumber claim",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<MeWalletResponse>> wallet(HttpServletRequest request) {
        String phone = requirePhone();
        List<LoyaltyUser> projections = users.findByPhoneNumber(phone);
        // Points are GLOBAL per customer now: one wallet (MAIN + any pockets)
        // keyed by phone, so the total is a direct read — no per-tenant collapse.
        BigDecimal totalPoints = wallets.findByPhoneNumber(phone).stream()
                .map(w -> w.getBalance() == null ? BigDecimal.ZERO : w.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalVouchers = 0L;
        if (!projections.isEmpty()) {
            // Vouchers are still per LoyaltyUser projection; sum across tenants.
            List<UUID> userIds = projections.stream().map(LoyaltyUser::getId).toList();
            for (Object[] row : vouchers.countActiveGroupedByUserId(userIds)) {
                totalVouchers += (Long) row[1];
            }
        }
        MeWalletResponse body = new MeWalletResponse(phone, totalPoints, totalVouchers);

        // ETag = stable hash of the response payload. The mini-app polls this
        // every few seconds while in foreground; with a matching If-None-Match
        // we short-circuit to 304 (no body, no work) — saves bandwidth and
        // gives a free "did anything change?" signal.
        String etag = "\"" + Integer.toHexString(body.hashCode()) + "\"";
        String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate())
                    .build();
        }
        // 30s private cache: short enough that "I just got points" feels live,
        // long enough to absorb a poll loop. private = browsers/clients only,
        // never a shared cache.
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate())
                .body(ApiResult.ok("Wallet retrieved", body));
    }

    public record MeWalletResponse(String phoneNumber, BigDecimal totalPoints, long totalVouchers) {}

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get the caller's own loyalty accounts",
            description = """
                    Returns every loyalty account belonging to the caller's phone, one per tenant they \
                    have transacted with.

                    **This is what makes the authenticated spend endpoints callable.** \
                    `POST /loyalty/transfer` needs a `fromUserId` and `POST /loyalty/redeem` a `userId` — \
                    both loyalty account UUIDs — and every tenant-scoped call needs an `X-Tenant-Id`. \
                    A customer app holds a phone number and a token, not either of those. This one call \
                    supplies both.

                    Resolved entirely from the JWT's `phoneNumber` claim, so it can only ever return the \
                    caller's own rows — there is no path variable or filter to point at somebody else.

                    An empty `accounts` list is a normal answer: the customer has proved their phone but \
                    has not yet transacted anywhere, so no projection exists. Points and vouchers are \
                    still readable via `/loyalty/users/me/wallet`."""
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Accounts retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Transacted with two tenants", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Accounts retrieved",
                                              "data": {
                                                "phoneNumber": "+263771234567",
                                                "accounts": [
                                                  {
                                                    "userId": "11111111-2222-3333-4444-555555555555",
                                                    "tenantId": "0a571c1c-7c75-4000-a000-000000000001",
                                                    "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                                    "status": "ACTIVE"
                                                  },
                                                  {
                                                    "userId": "99999999-8888-7777-6666-555555555555",
                                                    "tenantId": "0a571c1c-7c75-4000-a000-000000000002",
                                                    "merchantId": null,
                                                    "status": "ACTIVE"
                                                  }
                                                ]
                                              }
                                            }"""),
                                    @ExampleObject(name = "Registered but not yet transacted", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Accounts retrieved",
                                              "data": {
                                                "phoneNumber": "+263771234567",
                                                "accounts": []
                                              }
                                            }""")})),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "NO_PHONE_CLAIM — the JWT carries no phoneNumber",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "NO_PHONE_CLAIM",
                                      "message": "/me endpoints require a JWT with a phoneNumber claim",
                                      "data": null
                                    }""")))
    })
    public ResponseEntity<ApiResult<MeAccountsResponse>> accounts() {
        // The caller's identity comes from the token, never from the request.
        // That is the whole reason this endpoint can be `isAuthenticated()`
        // rather than carrying an ownership check: there is no input to bind
        // against, so there is nothing to point at another customer.
        String phone = requirePhone();

        List<MeAccount> accounts = users.findByPhoneNumber(phone).stream()
                .map(u -> new MeAccount(u.getId(), u.getTenantId(), u.getMerchantId(),
                        u.getStatus() == null ? null : u.getStatus().name()))
                .toList();

        // Deliberately NOT filtered to ACTIVE. A PENDING row is exactly the one
        // a customer needs to see explained ("you can earn but not spend yet"),
        // and hiding it would leave the app unable to say why a redeem was
        // refused. The status is reported so the client can decide.
        return ResponseEntity.ok(ApiResult.ok("Accounts retrieved",
                new MeAccountsResponse(phone, accounts)));
    }

    @GetMapping("/transactions")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get the caller's points statement across every tenant",
            description = """
                    Every earn, redemption, reversal and adjustment on the caller's loyalty accounts, \
                    newest first, across every tenant and merchant. One call replaces a \
                    `GET /loyalty/users/{id}/transactions` per account, and needs no `X-Tenant-Id`.

                    Resolved entirely from the JWT's `phoneNumber` claim: there is no path variable or \
                    filter, so it can only ever return the caller's own rows.

                    Paged with `page` (from 0) and `size` (default 20, at most 100). `balanceAfter` is \
                    always `null` on this read; the current balance is `GET /loyalty/users/me/wallet`. \
                    A customer with no account yet gets an empty page."""
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Statement retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Statement", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Transactions retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "22222222-3333-4444-5555-666666666666",
                                            "type": "REDEMPTION",
                                            "amount": 5.00,
                                            "pointsDelta": -500.0000,
                                            "balanceAfter": null,
                                            "ruleId": null,
                                            "campaignId": null,
                                            "shopId": "c7d8e9f0-1234-5678-90ab-cdef12345678",
                                            "postedBy": "e9d3b7a2-6f10-4c58-9b21-7d4e8a5c3f01",
                                            "channel": null,
                                            "reference": "POS-20260505-0007",
                                            "createdAt": "2026-05-05T09:30:00Z",
                                            "invoiceId": null,
                                            "currency": "USD",
                                            "baseAmount": 5.00
                                          },
                                          {
                                            "id": "11111111-2222-3333-4444-555555555555",
                                            "type": "PURCHASE",
                                            "amount": 100.00,
                                            "pointsDelta": 100.0000,
                                            "balanceAfter": null,
                                            "ruleId": "d6e2f4a5-4567-8901-bcde-f01234567890",
                                            "campaignId": null,
                                            "shopId": "c7d8e9f0-1234-5678-90ab-cdef12345678",
                                            "postedBy": "e9d3b7a2-6f10-4c58-9b21-7d4e8a5c3f01",
                                            "channel": "TYPED_PHONE",
                                            "reference": "POS-20260504-0001",
                                            "createdAt": "2026-05-04T11:00:00Z",
                                            "invoiceId": null,
                                            "currency": "USD",
                                            "baseAmount": 100.00
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 2,
                                        "totalPages": 1,
                                        "first": true,
                                        "last": true
                                      }
                                    }"""))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "NO_PHONE_CLAIM — the JWT carries no phoneNumber",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = NO_PHONE_EXAMPLE))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "No token, or an invalid one")
    })
    public ResponseEntity<ApiResult<PageResponse<Dtos.TransactionResponse>>> transactions(
            @ParameterObject Pageable pageable) {
        String phone = requirePhone();
        return ResponseEntity.ok(ApiResult.ok("Transactions retrieved successfully", PageResponse.from(
                transactionService.statementForPhone(accountIds(phone), capped(pageable)))));
    }

    @GetMapping("/vouchers")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get the caller's live vouchers across every tenant",
            description = """
                    Every voucher the caller holds that can still be used (ISSUED, VIEWED or \
                    PARTIALLY_USED), across every tenant, codes included: they are the caller's own \
                    vouchers. One call replaces a \
                    `GET /loyalty/vouchers/users/by-phone/{phoneNumber}/active` per tenant, and needs no \
                    `X-Tenant-Id`.

                    Each voucher carries its `tenantId`: send it as `X-Tenant-Id` to redeem \
                    (`POST /loyalty/vouchers/redeem`) or transfer (`POST /loyalty/vouchers/{id}/transfer`) \
                    that voucher.

                    Resolved entirely from the JWT's `phoneNumber` claim: there is no path variable or \
                    filter, so it can only ever return the caller's own vouchers. Paged with `page` \
                    (from 0) and `size` (default 20, at most 100)."""
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Vouchers retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "One live voucher", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Active vouchers retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "5b1f6c2a-9d3e-4f70-8a1b-2c3d4e5f6a7b",
                                            "code": "4829137605128368",
                                            "status": "PARTIALLY_USED",
                                            "voucherType": "SINGLE_USE",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "shopId": null,
                                            "batchId": null,
                                            "campaignSource": null,
                                            "assignedUserId": "11111111-2222-3333-4444-555555555555",
                                            "assigneePhone": "+263771234567",
                                            "assigneeName": "Sedrick Nyanyiwa",
                                            "senderName": "Tawanda Mpofu",
                                            "senderPhone": "+263782608767",
                                            "issuerUserId": "e9d3b7a2-6f10-4c58-9b21-7d4e8a5c3f01",
                                            "issuerPhone": null,
                                            "issuerEmail": "shopadmin@westgate.co.zw",
                                            "usesRemaining": 1,
                                            "value": 50.0000,
                                            "currency": "USD",
                                            "baseValue": 50.0000,
                                            "issuedAt": "2026-09-01T08:00:00Z",
                                            "deliveredAt": "2026-09-01T08:00:00Z",
                                            "viewedAt": "2026-09-01T09:12:00Z",
                                            "redeemedAt": null,
                                            "transferredAt": null,
                                            "expiresAt": "2027-09-01T08:00:00Z",
                                            "transferredFromUserId": null,
                                            "transferredFromPhone": null,
                                            "remainingBalance": 30.0000,
                                            "tenantId": "0a571c1c-7c75-4000-a000-000000000001"
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 1,
                                        "totalPages": 1,
                                        "first": true,
                                        "last": true
                                      }
                                    }"""))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "NO_PHONE_CLAIM — the JWT carries no phoneNumber",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = NO_PHONE_EXAMPLE))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "No token, or an invalid one")
    })
    public ResponseEntity<ApiResult<PageResponse<Dtos.VoucherResponse>>> vouchers(
            @ParameterObject Pageable pageable) {
        String phone = requirePhone();
        List<UUID> accountIds = accountIds(phone);
        Pageable page = capped(pageable);
        Page<Dtos.VoucherResponse> live = accountIds.isEmpty()
                ? Page.empty(page)
                : vouchers.findByAssignedUserIdInAndStatusIn(accountIds, Voucher.LIVE_STATUSES, page)
                        .map(VoucherService::toResponse);
        return ResponseEntity.ok(ApiResult.ok("Active vouchers retrieved successfully",
                PageResponse.from(live)));
    }

    /** The caller's phone, from the token; every /me read is keyed by it alone. */
    private static String requirePhone() {
        String phone = CallerDetails.currentPhoneNumber();
        if (phone == null || phone.isBlank()) {
            throw LoyaltyException.badRequest("NO_PHONE_CLAIM",
                    "/me endpoints require a JWT with a phoneNumber claim");
        }
        return phone;
    }

    private List<UUID> accountIds(String phone) {
        return users.findByPhoneNumber(phone).stream().map(LoyaltyUser::getId).toList();
    }

    /** Default 20 a page, never more than 100. */
    private static Pageable capped(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE);
        }
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }

    /**
     * One loyalty account. {@code userId} is what the spend endpoints want as
     * {@code fromUserId} / {@code userId}; {@code tenantId} is what belongs in
     * the {@code X-Tenant-Id} header on the same call.
     */
    public record MeAccount(UUID userId, UUID tenantId, UUID merchantId, String status) {}

    public record MeAccountsResponse(String phoneNumber, List<MeAccount> accounts) {}
}
