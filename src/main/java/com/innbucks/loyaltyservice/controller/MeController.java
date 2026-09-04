package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
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

    private final LoyaltyUserRepository users;
    private final VoucherRepository vouchers;
    private final WalletRepository wallets;

    public MeController(LoyaltyUserRepository users,
                        VoucherRepository vouchers,
                        WalletRepository wallets) {
        this.users = users;
        this.vouchers = vouchers;
        this.wallets = wallets;
    }

    @GetMapping("/wallet")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get the caller's total balance + voucher count across every tenant",
            description = "Aggregates every LoyaltyUser projection matching the caller's phone into a single " +
                          "wallet view — totalPoints is the sum of all points balances (including PENDING / " +
                          "INACTIVE accruals); totalVouchers is the count of voucher rows currently in any of " +
                          "ISSUED / DELIVERED / VIEWED / PARTIALLY_USED states. " +
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
                    responseCode = "400", description = "JWT has no phoneNumber claim",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "/me endpoints require a JWT with a phoneNumber claim",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<MeWalletResponse>> wallet(HttpServletRequest request) {
        String phone = CallerDetails.currentPhoneNumber();
        if (phone == null || phone.isBlank()) {
            throw LoyaltyException.badRequest("NO_PHONE_CLAIM",
                    "/me endpoints require a JWT with a phoneNumber claim");
        }
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
        String phone = CallerDetails.currentPhoneNumber();
        if (phone == null || phone.isBlank()) {
            throw LoyaltyException.badRequest("NO_PHONE_CLAIM",
                    "/me endpoints require a JWT with a phoneNumber claim");
        }

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

    /**
     * One loyalty account. {@code userId} is what the spend endpoints want as
     * {@code fromUserId} / {@code userId}; {@code tenantId} is what belongs in
     * the {@code X-Tenant-Id} header on the same call.
     */
    public record MeAccount(UUID userId, UUID tenantId, UUID merchantId, String status) {}

    public record MeAccountsResponse(String phoneNumber, List<MeAccount> accounts) {}
}
