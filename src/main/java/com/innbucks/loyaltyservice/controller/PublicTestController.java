package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.service.RedemptionService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.TransferService;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>TEST-ONLY, UNAUTHENTICATED endpoints.</b> Everything under
 * {@code /loyalty/public/**} is reachable with <b>no JWT, no bearer token, no
 * tenant header and no role</b> — it exists so a frontend can be built and
 * demoed against real data before its auth flow is wired up.
 *
 * <p>This lives in its own controller, under its own path prefix and its own
 * Swagger tag, purely so the split is impossible to miss: everything in the
 * other controllers is authenticated, everything here is not.
 *
 * <h2>How identity works without a token</h2>
 *
 * There is no caller, so <b>the phone number in the URL IS the identity</b>.
 * Each endpoint resolves that phone to its loyalty account and then runs the
 * SAME production service method the authenticated endpoint runs, with a
 * synthesised {@code CUSTOMER} authentication for that customer (see
 * {@link #asCustomer}). That matters: the ownership and single-hop rules are
 * not bypassed or re-implemented here, they execute exactly as they do in
 * production. Only the way the caller is identified differs.
 *
 * <h2>How the tenant is resolved without a header</h2>
 *
 * {@code X-Tenant-Id} is deliberately not accepted. Instead:
 * <ul>
 *   <li>Reads that are genuinely global need no tenant at all — wallets are
 *       keyed by phone, and the statement/voucher lists aggregate across every
 *       tenant projection the phone has.</li>
 *   <li>A voucher operation takes the tenant from the <b>voucher row itself</b>.</li>
 *   <li>The points writes resolve it: the configured override if set, else the
 *       phone's single projection. If the phone belongs to more than one tenant
 *       the request is refused rather than guessed — see
 *       {@link #resolveTenant}.</li>
 * </ul>
 *
 * <h2>Rules for anything added under this prefix</h2>
 * <ol>
 *   <li><b>Off unless explicitly switched on.</b> Gated by
 *       {@code loyalty.public-test.enabled}, default {@code false}. A cell that
 *       forgets to set it serves 404s, which is the safe direction.</li>
 *   <li><b>Never call a service method this controller re-implements.</b> The
 *       point of these endpoints is to exercise the real behaviour; a
 *       convenience shortcut that skips a guard would make the test surface
 *       lie about production.</li>
 *   <li><b>Every call is logged</b> at WARN with a masked phone, so there is a
 *       trail of what was done while the switch was on.</li>
 * </ol>
 *
 * <p><b>This must not be enabled on a production cell.</b> A phone number is
 * guessable, and these endpoints now MOVE VALUE as well as read it — an enabled
 * cell lets anyone who can guess a number spend that customer's points and
 * vouchers. That is an accepted trade for a staging environment and nowhere
 * else.
 */
@RestController
@RequestMapping("/loyalty/public")
@Slf4j
@Tag(name = "Public (TEST ONLY — no auth)",
     description = """
             **Unauthenticated endpoints for frontend testing. No bearer token, no tenant header, no role.**

             Send NOTHING but the request itself — no `Authorization`, no `X-Tenant-Id`. These exist so \
             the app can be built against real data before its auth flow is wired up, and they are \
             disabled unless the cell sets `LOYALTY_PUBLIC_TEST_ENABLED=true`.

             The phone number in the URL is the identity. Each endpoint runs the same production \
             service method as its authenticated twin, so the real rules (ownership, single-hop voucher \
             transfer, insufficient funds) all still apply — only the way the caller is identified differs.

             Every other tag in this document requires a bearer token. If you are looking for the \
             endpoint you will SHIP against, it is there, not here.

             **Do not point a production build at these.** A phone number is guessable, and these move \
             value as well as read it.""")
@SecurityRequirements   // documents "no auth" — overrides the global bearerAuth requirement
public class PublicTestController {

    /** Hard ceiling on page size — a public endpoint must not accept `?size=100000`. */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final List<Voucher.Status> ACTIVE_VOUCHER_STATUSES = List.of(
            Voucher.Status.ISSUED, Voucher.Status.DELIVERED,
            Voucher.Status.VIEWED, Voucher.Status.PARTIALLY_USED);

    private final LoyaltyUserRepository users;
    private final WalletRepository wallets;
    private final VoucherRepository vouchers;
    private final MerchantRepository merchants;
    private final TransactionService transactions;
    private final TransferService transfers;
    private final RedemptionService redemptions;
    private final VoucherService voucherService;

    /**
     * Master switch. Default {@code false} so the endpoints are absent unless a
     * cell deliberately turns them on — the same fail-closed posture
     * {@code ProductionSecretsGuard} takes for secrets.
     */
    @Value("${loyalty.public-test.enabled:false}")
    private boolean enabled;

    /**
     * Optional tenant pin for the points writes. Only consulted when set; when
     * blank the tenant comes from the phone's own projection. Exists for a cell
     * that has several tenants, where the phone alone is ambiguous.
     */
    @Value("${loyalty.public-test.tenant-id:}")
    private String configuredTenantId;

    /** Optional merchant pin, same rationale as the tenant one. */
    @Value("${loyalty.public-test.merchant-id:}")
    private String configuredMerchantId;

    public PublicTestController(LoyaltyUserRepository users,
                                WalletRepository wallets,
                                VoucherRepository vouchers,
                                MerchantRepository merchants,
                                TransactionService transactions,
                                TransferService transfers,
                                RedemptionService redemptions,
                                VoucherService voucherService) {
        this.users = users;
        this.wallets = wallets;
        this.vouchers = vouchers;
        this.merchants = merchants;
        this.transactions = transactions;
        this.transfers = transfers;
        this.redemptions = redemptions;
        this.voucherService = voucherService;
    }

    @PostConstruct
    void warnIfEnabled() {
        if (enabled) {
            log.warn("PUBLIC TEST endpoints are ENABLED (/loyalty/public/**). Unauthenticated reads AND "
                    + "WRITES against customer wallets and vouchers are live on this cell. "
                    + "This must NOT be a production cell.");
        }
    }

    // =====================================================================
    // Reads
    // =====================================================================

    @GetMapping("/customers/{phoneNumber}/wallet")
    @Operation(summary = "[TEST — no auth] Points wallet for a customer, by phone number",
            description = """
                    Total points and active-voucher count for the phone, aggregated across every tenant.

                    Points are GLOBAL per customer — one wallet keyed by phone — so this needs no tenant \
                    at all. `totalVouchers` counts vouchers in ISSUED / DELIVERED / VIEWED / \
                    PARTIALLY_USED.

                    **Authenticated equivalent:** `GET /loyalty/users/me/wallet`.""")
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
                    responseCode = "404", description = "Public test endpoints are disabled on this cell")
    })
    public ResponseEntity<ApiResult<PublicWalletResponse>> wallet(
            @Parameter(description = "Customer phone in E.164 form", example = "+263771234567")
            @PathVariable String phoneNumber) {
        requireEnabled();
        String phone = requirePhone(phoneNumber, "wallet");

        BigDecimal totalPoints = wallets.findByPhoneNumber(phone).stream()
                .map(w -> w.getBalance() == null ? BigDecimal.ZERO : w.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long totalVouchers = 0L;
        List<UUID> userIds = projectionIds(phone);
        if (!userIds.isEmpty()) {
            for (Object[] row : vouchers.countActiveGroupedByUserId(userIds)) {
                totalVouchers += (Long) row[1];
            }
        }
        return ResponseEntity.ok(ApiResult.ok("Wallet retrieved",
                new PublicWalletResponse(phone, totalPoints, totalVouchers)));
    }

    @GetMapping("/customers/{phoneNumber}/transactions")
    @Operation(summary = "[TEST — no auth] Points statement for a customer, by phone number",
            description = """
                    Every earn, redemption, reversal and adjustment, newest first, across every merchant \
                    and every tenant the phone has transacted with.

                    `balanceAfter` is always `null` here: the running balance is only recorded on write \
                    paths, and computing it per row would cost a wallet lookup each. Use the wallet \
                    endpoint for the current balance.

                    An unknown phone returns an empty page, not a 404 — to a caller holding only a phone \
                    number, "not a customer" and "no activity yet" are deliberately the same answer.

                    **Authenticated equivalent:** `GET /loyalty/users/{id}/transactions`.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Statement retrieved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Public test endpoints are disabled on this cell")
    })
    public ResponseEntity<ApiResult<PageResponse<Dtos.TransactionResponse>>> transactionsByPhone(
            @Parameter(description = "Customer phone in E.164 form", example = "+263771234567")
            @PathVariable String phoneNumber,
            @ParameterObject Pageable pageable) {
        requireEnabled();
        String phone = requirePhone(phoneNumber, "statement");
        PageResponse<Dtos.TransactionResponse> data = PageResponse.from(
                transactions.statementForPhone(projectionIds(phone), capped(pageable)));
        return ResponseEntity.ok(ApiResult.ok("Transactions retrieved successfully", data));
    }

    @GetMapping("/customers/{phoneNumber}/vouchers")
    @Operation(summary = "[TEST — no auth] Active vouchers for a customer, by phone number",
            description = """
                    Vouchers in an active state (ISSUED / DELIVERED / VIEWED / PARTIALLY_USED) held by \
                    the phone, across every tenant.

                    `valueType` drives rendering: PERCENT → "10% off", AMOUNT → currency-formatted using \
                    `currency`, FREE_ITEM / COMBO → ignore `value`, it may be null. `value` and \
                    `currency` are a snapshot frozen at issuance, so render what the voucher carries \
                    rather than re-deriving from the template.

                    **Authenticated equivalent:** \
                    `GET /loyalty/vouchers/users/by-phone/{phoneNumber}/active` (which is scoped to the \
                    tenant on the header; this one is not).""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Vouchers retrieved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Public test endpoints are disabled on this cell")
    })
    public ResponseEntity<ApiResult<PageResponse<Dtos.VoucherResponse>>> vouchersByPhone(
            @Parameter(description = "Customer phone in E.164 form", example = "+263771234567")
            @PathVariable String phoneNumber,
            @ParameterObject Pageable pageable) {
        requireEnabled();
        String phone = requirePhone(phoneNumber, "vouchers");
        List<UUID> userIds = projectionIds(phone);
        if (userIds.isEmpty()) {
            return ResponseEntity.ok(ApiResult.ok("Active vouchers retrieved successfully",
                    PageResponse.from(org.springframework.data.domain.Page.empty(capped(pageable)))));
        }
        var page = vouchers.findByAssignedUserIdInAndStatusIn(
                userIds, ACTIVE_VOUCHER_STATUSES, capped(pageable)).map(VoucherService::toResponse);
        return ResponseEntity.ok(ApiResult.ok("Active vouchers retrieved successfully",
                PageResponse.from(page)));
    }

    // =====================================================================
    // Writes
    // =====================================================================

    @PostMapping("/customers/{phoneNumber}/points/send")
    @Operation(summary = "[TEST — no auth] Send points to another customer (P2P)",
            description = """
                    Moves points from the phone in the URL to `toPhone`. The sender is the URL's phone — \
                    there is no `fromUserId` to supply, it is resolved for you.

                    Runs the SAME service method as the authenticated endpoint, so every real rule still \
                    applies: the sender must be registered (you cannot spend a PENDING balance), an \
                    unknown `toPhone` is auto-enrolled PENDING, self-transfer is refused, and \
                    INSUFFICIENT_FUNDS is returned when the balance is short.

                    **Authenticated equivalent:** `POST /loyalty/transfer`.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Points sent; returns the sender's new balance",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Points transferred successfully",
                                      "data": { "newBalance": 75.00 }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "BAD_AMOUNT, SELF_TRANSFER, INSUFFICIENT_FUNDS, or AMBIGUOUS_TENANT "
                                + "(the phone belongs to more than one tenant — pin one with "
                                + "LOYALTY_PUBLIC_TEST_TENANT_ID)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Public test endpoints are disabled, or no such customer")
    })
    public ResponseEntity<ApiResult<Map<String, Object>>> sendPoints(
            @Parameter(description = "SENDER's phone in E.164 form", example = "+263771111111")
            @PathVariable String phoneNumber,
            @Valid @RequestBody PublicSendPointsRequest body) {
        requireEnabled();
        String phone = requirePhone(phoneNumber, "send-points");
        LoyaltyUser sender = requireSingleProjection(phone);

        BigDecimal balance = asCustomer(sender, () -> transfers.transfer(
                sender.getTenantId(),
                new Dtos.TransferRequest(sender.getId(), null, body.toPhone(),
                        body.points(), body.reason())));
        return ResponseEntity.ok(ApiResult.ok("Points transferred successfully",
                Map.of("newBalance", balance)));
    }

    @PostMapping("/customers/{phoneNumber}/points/redeem")
    @Operation(summary = "[TEST — no auth] Redeem a customer's points",
            description = """
                    Burns points from the phone in the URL at a merchant.

                    `merchantId` may be omitted when the tenant has exactly one merchant (the usual case \
                    on a test cell) or when `LOYALTY_PUBLIC_TEST_MERCHANT_ID` is pinned; otherwise supply \
                    it. `reference` is an idempotency key — a repeat with the same (merchant, reference) \
                    replays the original instead of debiting twice.

                    **Authenticated equivalent:** `POST /loyalty/redeem`.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Points redeemed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Points redeemed successfully",
                                      "data": {
                                        "status": "OK",
                                        "transactionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                        "newBalance": 25.00
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INSUFFICIENT_FUNDS, AMBIGUOUS_TENANT, or AMBIGUOUS_MERCHANT "
                                + "(supply merchantId, or pin LOYALTY_PUBLIC_TEST_MERCHANT_ID)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Public test endpoints are disabled, or no such customer")
    })
    public ResponseEntity<ApiResult<Map<String, Object>>> redeemPoints(
            @Parameter(description = "Customer phone in E.164 form", example = "+263771234567")
            @PathVariable String phoneNumber,
            @Valid @RequestBody PublicRedeemPointsRequest body) {
        requireEnabled();
        String phone = requirePhone(phoneNumber, "redeem-points");
        LoyaltyUser customer = requireSingleProjection(phone);
        UUID merchantId = resolveMerchant(customer.getTenantId(), body.merchantId());

        RedemptionService.RedemptionResult result = asCustomer(customer, () ->
                redemptions.redeemPoints(customer.getTenantId(), merchantId,
                        new Dtos.RedemptionRequest(merchantId, customer.getId(), body.points(),
                                body.reason(), body.reference()),
                        true));
        return ResponseEntity.ok(ApiResult.ok("Points redeemed successfully", Map.of(
                "status", "OK",
                "transactionId", result.transactionId(),
                "newBalance", result.balance())));
    }

    @PostMapping("/vouchers/{voucherId}/transfer")
    @Operation(summary = "[TEST — no auth] Transfer a voucher to another customer (ONE hop only)",
            description = """
                    Hands the voucher to `toPhone`. The current holder is read from the voucher itself, \
                    so no sender phone is needed.

                    **A voucher can only be transferred ONCE** — a second attempt is refused with \
                    `VOUCHER_ALREADY_TRANSFERRED`. Only an unused, live voucher moves (ISSUED / \
                    DELIVERED / VIEWED); PARTIALLY_USED and the terminal states are refused, as is an \
                    expired one. All of that is the production rule, running unchanged.

                    The tenant comes from the voucher row, so nothing needs pinning here.

                    **Authenticated equivalent:** `POST /loyalty/vouchers/{id}/transfer`.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Transferred; the response shows the NEW assignee"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "VOUCHER_ALREADY_TRANSFERRED, VOUCHER_NOT_TRANSFERABLE, "
                                + "VOUCHER_EXPIRED, SELF_TRANSFER, or RECIPIENT_REQUIRED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Public test endpoints are disabled, or no such voucher")
    })
    public ResponseEntity<ApiResult<Dtos.VoucherResponse>> transferVoucher(
            @PathVariable UUID voucherId,
            @Valid @RequestBody PublicVoucherTransferRequest body) {
        requireEnabled();
        Voucher v = vouchers.findById(voucherId)
                .orElseThrow(() -> LoyaltyException.notFound("voucher"));
        log.warn("PUBLIC TEST voucher transfer voucherId={} to={}",
                voucherId, MsisdnMasking.mask(body.toPhone()));

        LoyaltyUser holder = holderOf(v);
        Dtos.VoucherResponse data = asCustomer(holder, () -> voucherService.transfer(
                v.getTenantId(), voucherId,
                new Dtos.VoucherTransferRequest(null, body.toPhone(), body.note())));
        return ResponseEntity.ok(ApiResult.ok("Voucher transferred successfully", data));
    }

    @PostMapping("/vouchers/redeem")
    @Operation(summary = "[TEST — no auth] Redeem a voucher by code",
            description = """
                    Redeems the voucher identified by `code`. The tenant comes from the voucher row; \
                    `merchantId` may be omitted when the voucher already names one.

                    Runs the production redemption path, so the real guards apply — wrong merchant, \
                    already redeemed, expired, usage exceeded — and a multi-use voucher comes back \
                    PARTIALLY_USED with a remaining count rather than REDEEMED.

                    **Authenticated equivalent:** `POST /loyalty/vouchers/redeem`.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Voucher redeemed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Voucher redeemed successfully",
                                      "data": {
                                        "redemptionId": "5c2f1e90-1111-2222-3333-444455556666",
                                        "voucherId": "9f8e7d6c-5b4a-3210-fedc-ba9876543210",
                                        "status": "REDEEMED",
                                        "usesRemaining": 0,
                                        "value": 10.0000,
                                        "valueType": "PERCENT",
                                        "redeemedAt": "2026-08-25T14:02:00Z"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Already redeemed, expired, usage exceeded, or wrong merchant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Public test endpoints are disabled, or no such voucher code")
    })
    public ResponseEntity<ApiResult<Dtos.RedemptionResponse>> redeemVoucher(
            @Valid @RequestBody PublicRedeemVoucherRequest body) {
        requireEnabled();
        Voucher v = vouchers.findByCode(body.code())
                .orElseThrow(() -> LoyaltyException.notFound("voucher"));
        log.warn("PUBLIC TEST voucher redeem code={} voucherId={}", body.code(), v.getId());

        UUID merchantId = body.merchantId() != null
                ? body.merchantId()
                : (v.getMerchantId() != null ? v.getMerchantId()
                                             : resolveMerchant(v.getTenantId(), null));
        LoyaltyUser holder = holderOf(v);
        Dtos.RedemptionResponse data = asCustomer(holder, () -> voucherService.redeem(
                v.getTenantId(), merchantId,
                new Dtos.RedeemVoucherRequest(merchantId, body.code(),
                        holder == null ? null : holder.getId(), null, null, null)));
        return ResponseEntity.ok(ApiResult.ok("Voucher redeemed successfully", data));
    }

    // =====================================================================
    // Plumbing
    // =====================================================================

    /**
     * Run {@code work} as if the given customer were the authenticated caller.
     *
     * <p>This is what lets the public endpoints call the REAL service methods
     * instead of re-implementing them. Those methods check ownership through
     * {@link CallerDetails} ("is the caller the voucher's holder?", "does the
     * caller own the sending wallet?"), and with no token there is no caller,
     * so they would refuse everything.
     *
     * <p>Synthesising the authentication here — rather than adding a "test mode"
     * bypass inside the services — is the important part: <b>not one production
     * guard is weakened or skipped.</b> Each rule executes exactly as it does
     * for a real logged-in customer; only the way that customer was identified
     * differs. A bypass flag threaded through the services would be a permanent
     * hole in the real code paths for the sake of a test surface.
     *
     * <p>The context is cleared in a finally so nothing leaks onto the thread
     * for the next request the container serves on it.
     */
    private <T> T asCustomer(LoyaltyUser customer, Supplier<T> work) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        try {
            if (customer != null) {
                var auth = new UsernamePasswordAuthenticationToken(
                        "public-test@" + MsisdnMasking.mask(customer.getPhoneNumber()),
                        null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
                auth.setDetails(new CallerDetails(null, null,
                        customer.getPhoneNumber(), customer.getId()));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            return work.get();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
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

    private String requirePhone(String phoneNumber, String op) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw LoyaltyException.badRequest("PHONE_REQUIRED", "phoneNumber is required");
        }
        // Audit trail: unauthenticated access to someone's loyalty account.
        log.warn("PUBLIC TEST {} phoneNumber={}", op, MsisdnMasking.mask(phoneNumber));
        return phoneNumber;
    }

    private List<UUID> projectionIds(String phone) {
        return users.findByPhoneNumber(phone).stream().map(LoyaltyUser::getId).toList();
    }

    /**
     * The single loyalty account for a phone, for operations that must name one
     * tenant. Refuses rather than guesses when the phone spans several tenants:
     * picking one arbitrarily would move points in a tenant the caller never
     * chose, and the failure would be silent.
     */
    private LoyaltyUser requireSingleProjection(String phone) {
        List<LoyaltyUser> found = users.findByPhoneNumber(phone);
        if (found.isEmpty()) {
            throw LoyaltyException.notFound("customer");
        }
        UUID pinned = parseUuidOrNull(configuredTenantId);
        if (pinned != null) {
            return found.stream()
                    .filter(u -> pinned.equals(u.getTenantId()))
                    .findFirst()
                    .orElseThrow(() -> LoyaltyException.notFound("customer in the configured test tenant"));
        }
        if (found.size() > 1) {
            throw LoyaltyException.badRequest("AMBIGUOUS_TENANT",
                    "This phone belongs to more than one tenant. Pin one with "
                            + "LOYALTY_PUBLIC_TEST_TENANT_ID to use the public test writes.");
        }
        return found.get(0);
    }

    /** Explicit id wins; else the configured pin; else the tenant's only merchant. */
    private UUID resolveMerchant(UUID tenantId, UUID explicit) {
        if (explicit != null) {
            return explicit;
        }
        UUID pinned = parseUuidOrNull(configuredMerchantId);
        if (pinned != null) {
            return pinned;
        }
        List<Merchant> all = merchants.findByTenantId(tenantId);
        if (all.size() == 1) {
            return all.get(0).getId();
        }
        throw LoyaltyException.badRequest("AMBIGUOUS_MERCHANT",
                all.isEmpty()
                        ? "This tenant has no merchant configured."
                        : "This tenant has " + all.size() + " merchants. Supply merchantId, or pin "
                          + "LOYALTY_PUBLIC_TEST_MERCHANT_ID.");
    }

    /** The voucher's current holder, or null when it is unassigned (bulk-issued). */
    private LoyaltyUser holderOf(Voucher v) {
        if (v.getAssignedUserId() != null) {
            return users.findById(v.getAssignedUserId()).orElse(null);
        }
        if (v.getAssigneePhone() != null && !v.getAssigneePhone().isBlank()) {
            return users.findByPhoneNumber(v.getAssigneePhone()).stream().findFirst().orElse(null);
        }
        return null;
    }

    private static UUID parseUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
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

    // =====================================================================
    // Request / response shapes — deliberately minimal: a phone and an amount.
    // No tenantId, no fromUserId, no headers.
    // =====================================================================

    public record PublicWalletResponse(String phoneNumber, BigDecimal totalPoints, long totalVouchers) {}

    public record PublicSendPointsRequest(
            @io.swagger.v3.oas.annotations.media.Schema(example = "+263772222222",
                    description = "Recipient's phone. Auto-enrolled as PENDING if unknown.")
            @jakarta.validation.constraints.NotBlank String toPhone,
            @io.swagger.v3.oas.annotations.media.Schema(example = "250.0000")
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Positive BigDecimal points,
            @io.swagger.v3.oas.annotations.media.Schema(example = "Birthday gift", nullable = true)
            @jakarta.validation.constraints.Size(max = 1000) String reason) {}

    public record PublicRedeemPointsRequest(
            @io.swagger.v3.oas.annotations.media.Schema(example = "500.0000")
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Positive BigDecimal points,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true,
                    description = "Optional when the tenant has exactly one merchant.")
            UUID merchantId,
            @io.swagger.v3.oas.annotations.media.Schema(example = "Counter redemption", nullable = true)
            @jakarta.validation.constraints.Size(max = 1000) String reason,
            @io.swagger.v3.oas.annotations.media.Schema(example = "ORDER-4471", nullable = true,
                    description = "Idempotency key — a repeat with the same value replays the original.")
            String reference) {}

    public record PublicVoucherTransferRequest(
            @io.swagger.v3.oas.annotations.media.Schema(example = "+263772222222")
            @jakarta.validation.constraints.NotBlank String toPhone,
            @io.swagger.v3.oas.annotations.media.Schema(example = "Passing this on", nullable = true)
            @jakarta.validation.constraints.Size(max = 200) String note) {}

    public record PublicRedeemVoucherRequest(
            @io.swagger.v3.oas.annotations.media.Schema(example = "VCH-AB12CD34")
            @jakarta.validation.constraints.NotBlank String code,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true,
                    description = "Optional when the voucher already names a merchant.")
            UUID merchantId) {}
}
