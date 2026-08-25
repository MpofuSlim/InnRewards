package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * <b>TEST-ONLY, UNAUTHENTICATED endpoints.</b> Everything under
 * {@code /loyalty/public/**} is reachable with no JWT, no tenant header and no
 * role — it exists so a frontend can be built and demoed against real data
 * before its auth flow is wired up.
 *
 * <p>This lives in its own controller, under its own path prefix and its own
 * Swagger tag, purely so the split is impossible to miss: everything in the
 * other controllers is authenticated, everything here is not. Do not move an
 * endpoint in here to "make it easier to call", and do not add a write endpoint
 * here at all — see the rules below.
 *
 * <h2>Rules for anything added under this prefix</h2>
 * <ol>
 *   <li><b>Reads only.</b> No endpoint here may move points, redeem a voucher,
 *       transfer value or mutate any row. A phone number is a low-entropy,
 *       enumerable identifier; an unauthenticated write keyed on one is an open
 *       door to every customer's balance. The authenticated equivalents already
 *       exist for every such operation.</li>
 *   <li><b>Off unless explicitly switched on.</b> Gated by
 *       {@code loyalty.public-test.enabled}, default {@code false}. A cell that
 *       forgets to set it serves 404s, which is the safe direction. The ZW
 *       staging cell enables it via {@code LOYALTY_PUBLIC_TEST_ENABLED} in
 *       {@code deploy/cells/cell.zw.env} (that file lives in
 *       {@code ticketing-system}).</li>
 *   <li><b>Every call is logged</b> at WARN with a masked phone, so there is a
 *       trail of who read what while the switch was on.</li>
 * </ol>
 *
 * <p><b>This must not be enabled on a production cell.</b> A caller who can
 * guess a phone number can read that customer's entire loyalty history —
 * merchant, amounts, timestamps. That is an accepted trade for a staging
 * environment behind a known URL and nothing more.
 */
@RestController
@RequestMapping("/loyalty/public")
@Slf4j
@Tag(name = "Public (TEST ONLY — no auth)",
     description = """
             **Unauthenticated endpoints for frontend testing. No JWT, no tenant header, no role.**

             These exist so the app can be built against real data before its auth flow is wired \
             up. They are read-only and are disabled unless the cell sets \
             `LOYALTY_PUBLIC_TEST_ENABLED=true`. Every other tag in this document requires a \
             bearer token — if you are looking for the endpoint you will ship against, it is \
             there, not here.

             **Do not point a production build at these.** A phone number is guessable, so an \
             enabled cell leaks any customer's history to anyone who asks.""")
@SecurityRequirements   // explicitly documents "no auth" — overrides the global bearerAuth requirement
public class PublicTestController {

    /** Hard ceiling on page size — a public endpoint must not accept `?size=100000`. */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final LoyaltyUserRepository users;
    private final TransactionService transactions;

    /**
     * Master switch. Default {@code false} so the endpoints are absent unless a
     * cell deliberately turns them on — the same fail-closed posture
     * {@code ProductionSecretsGuard} takes for secrets.
     */
    @Value("${loyalty.public-test.enabled:false}")
    private boolean enabled;

    public PublicTestController(LoyaltyUserRepository users, TransactionService transactions) {
        this.users = users;
        this.transactions = transactions;
    }

    @PostConstruct
    void warnIfEnabled() {
        if (enabled) {
            log.warn("PUBLIC TEST endpoints are ENABLED (/loyalty/public/**). Unauthenticated reads of "
                    + "customer transaction history are live on this cell. This must NOT be a production cell.");
        }
    }

    @GetMapping("/customers/{phoneNumber}/transactions")
    @Operation(
            summary = "[TEST — no auth] Points statement for a customer, by phone number",
            description = """
                    Returns the customer's transaction history — every earn, redemption, reversal \
                    and adjustment — newest first, across every merchant they've transacted with.

                    A phone number can map to one loyalty account per tenant; this collapses all of \
                    them into a single feed, the same way the wallet endpoint collapses balances. \
                    `balanceAfter` is always `null` here: the running balance is only recorded on \
                    write paths, and computing it per row would cost a wallet lookup each.

                    An unknown phone returns an empty page, not a 404 — to a caller holding only a \
                    phone number, "not a customer" and "no activity yet" are deliberately the same \
                    answer.

                    **Authenticated equivalent:** `GET /loyalty/users/{id}/transactions`. Ship \
                    against that one.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Statement retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Transactions retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                            "type": "PURCHASE",
                                            "amount": 100.00,
                                            "pointsDelta": 10.00,
                                            "balanceAfter": null,
                                            "ruleId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "campaignId": null,
                                            "shopId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "postedBy": null,
                                            "channel": "CHECKOUT_S2S",
                                            "reference": "ORDER-4471",
                                            "createdAt": "2026-08-24T09:15:00Z",
                                            "invoiceId": null
                                          },
                                          {
                                            "id": "b6d7a1f2-3c4e-4a5b-8c9d-0e1f2a3b4c5d",
                                            "type": "REDEMPTION",
                                            "amount": 5.00,
                                            "pointsDelta": -5.00,
                                            "balanceAfter": null,
                                            "ruleId": null,
                                            "campaignId": null,
                                            "shopId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "postedBy": null,
                                            "channel": null,
                                            "reference": "VOUCHER:VCH-AB12CD",
                                            "createdAt": "2026-08-23T16:40:00Z",
                                            "invoiceId": null
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
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Blank phone number",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "phoneNumber is required",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Public test endpoints are disabled on this cell "
                                + "(`loyalty.public-test.enabled` is false — the default)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Public test endpoints are not enabled on this deployment",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<PageResponse<Dtos.TransactionResponse>>> transactionsByPhone(
            @Parameter(description = "Customer phone number in E.164 form, e.g. +263771234567",
                       example = "+263771234567")
            @PathVariable String phoneNumber,
            @ParameterObject Pageable pageable) {
        requireEnabled();
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw LoyaltyException.badRequest("PHONE_REQUIRED", "phoneNumber is required");
        }

        // Audit trail: this is an unauthenticated read of someone's history, so
        // every hit is recorded (masked) for as long as the switch is on.
        log.warn("PUBLIC TEST statement read phoneNumber={}", MsisdnMasking.mask(phoneNumber));

        List<UUID> userIds = users.findByPhoneNumber(phoneNumber).stream()
                .map(LoyaltyUser::getId)
                .toList();
        PageResponse<Dtos.TransactionResponse> data =
                PageResponse.from(transactions.statementForPhone(userIds, capped(pageable)));
        return ResponseEntity.ok(ApiResult.ok("Transactions retrieved successfully", data));
    }

    /**
     * 404 rather than 403 when the switch is off: an endpoint that isn't meant
     * to exist on this cell should look like it doesn't exist, not advertise
     * that there's a feature here waiting to be unlocked.
     */
    private void requireEnabled() {
        if (!enabled) {
            throw LoyaltyException.notFound("Public test endpoints are not enabled on this deployment");
        }
    }

    /** Clamp an unauthenticated caller's page size; keeps their sort/page index. */
    private Pageable capped(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE);
        }
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }
}
