package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.service.ShopCheckoutService;
import com.innbucks.loyaltyservice.service.TicketingLoyaltyService;
import com.innbucks.loyaltyservice.service.UserService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service-to-service endpoints consumed by other backends (today: user-service
 * at login + shop-staff creation, payment-service for shop-checkout). Gated
 * by a shared-secret header rather than the user JWT — the caller is another
 * microservice, not a logged-in user.
 *
 * <p>Class-level {@link Hidden} keeps every endpoint here out of the public
 * Swagger UI; the gateway additionally blocks {@code /loyalty/internal/**}
 * at the edge via the {@code loyalty-internal-deny} route. Per-method
 * {@code @ApiResponses} blocks are intentionally omitted as a result (the
 * endpoints never render in any consumer-facing spec); {@link Operation}
 * summaries are kept so anyone reading the code or running springdoc
 * locally still sees a one-line description per handler.
 */
@RestController
@RequestMapping("/loyalty/internal")
@Slf4j
@Hidden
public class InternalMerchantLookupController {

    private final MerchantRepository merchants;
    private final ShopRepository shops;
    private final UserService userService;
    private final ShopCheckoutService shopCheckoutService;
    private final TicketingLoyaltyService ticketingLoyaltyService;
    private final com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier;
    private final String expectedToken;

    public InternalMerchantLookupController(MerchantRepository merchants,
                                            ShopRepository shops,
                                            UserService userService,
                                            ShopCheckoutService shopCheckoutService,
                                            TicketingLoyaltyService ticketingLoyaltyService,
                                            com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier,
                                            @Value("${innbucks.internal-api-token:}") String expectedToken) {
        this.merchants = merchants;
        this.shops = shops;
        this.userService = userService;
        this.shopCheckoutService = shopCheckoutService;
        this.ticketingLoyaltyService = ticketingLoyaltyService;
        this.memberNotifier = memberNotifier;
        this.expectedToken = expectedToken;
    }

    @GetMapping("/merchants/by-admin")
    @Operation(summary = "(S2S) Resolve a merchant by admin email",
            description = "Returns the merchantId for the oldest merchant whose adminEmail matches the query. " +
                          "Used by user-service when a logged-in admin's JWT carries an email but not a merchantId.")
    public ResponseEntity<?> byAdminEmail(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                          @RequestParam("email") String email) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "email is required"));
        }
        Optional<Merchant> hit = merchants.findFirstByAdminEmailOrderByCreatedAtAsc(email);
        if (hit.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        UUID merchantId = hit.get().getId();
        log.debug("Internal lookup resolved adminEmail={} -> merchantId={}", email, merchantId);
        return ResponseEntity.ok(Map.of("merchantId", merchantId));
    }

    @GetMapping("/merchants/ids-by-admin")
    @Operation(summary = "(S2S) List every merchantId an admin owns",
            description = "Returns the ids of ALL merchants whose adminEmail matches the query " +
                          "(case-insensitive). Used by user-service to authorize a MERCHANT_ADMIN " +
                          "over shop-staff endpoints — a MERCHANT_ADMIN's JWT carries no merchantId, " +
                          "and they may run more than one merchant, so the whole set is needed.")
    public ResponseEntity<?> idsByAdminEmail(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                             @RequestParam("email") String email) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "email is required"));
        }
        java.util.List<UUID> merchantIds = merchants.findByAdminEmailIgnoreCase(email.trim()).stream()
                .map(Merchant::getId)
                .toList();
        log.debug("Internal lookup resolved adminEmail={} -> {} merchant(s)", email, merchantIds.size());
        return ResponseEntity.ok(Map.of("merchantIds", merchantIds));
    }

    /**
     * The INVERSE of {@link #idsByAdminEmail}: merchant → the email of the
     * person who runs it.
     *
     * <p>Every other consumer of this binding has arrived holding an email and
     * wanted the merchant. The marketplace arrives holding a merchant — a paid
     * order names a {@code merchantId} on every line — and needs to reach a
     * person. Nothing downstream could answer that: user-service stamps
     * {@code loyalty_merchant_id} on SHOP staff rows only, so a MERCHANT_ADMIN's
     * own user row does not name their merchant, and this column is the only
     * place the link is recorded.
     *
     * <p>Singular because the column is: one merchant has exactly one
     * {@code admin_email}. (One PERSON may run several merchants, which is why
     * the lookup in the other direction returns a list.)
     *
     * <p>A merchant with no admin email on file is a **200 with a null
     * {@code adminEmail}**, not a 404 — the merchant exists, and the consumer's
     * next step is identical either way (nobody to notify). The 404 is reserved
     * for a merchantId that names nothing, which is a genuinely different fact
     * and one the caller should see in its logs.
     */
    @GetMapping("/merchants/{id}/admin-email")
    @Operation(summary = "(S2S) The admin email of one merchant",
            description = "Returns {merchantId, adminEmail} for the given merchant — the inverse of "
                          + "ids-by-admin. Used by user-service to resolve a merchant's admin USER "
                          + "on behalf of marketplace-service, which knows a merchantId and needs to "
                          + "tell a person they have a paid order. adminEmail is null when the "
                          + "merchant has none on file; an unknown merchant is a 404.")
    public ResponseEntity<?> adminEmailForMerchant(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID id) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<Merchant> hit = merchants.findById(id);
        if (hit.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String adminEmail = hit.get().getAdminEmail();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", id);
        // LinkedHashMap, not Map.of — that one rejects a null value, and a
        // merchant with no admin on file is an ordinary answer here.
        body.put("adminEmail", adminEmail);
        log.debug("Internal lookup resolved merchantId={} -> adminEmail present={}",
                id, adminEmail != null && !adminEmail.isBlank());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/shops/{id}")
    @Operation(summary = "(S2S) Resolve a shop by id",
            description = "Returns the shop's tenantId + merchantId + status. Used by user-service " +
                          "when promoting a shop-staff user — needs the parent tenant to grant the " +
                          "right scope without trusting a client-supplied tenant claim.")
    public ResponseEntity<?> getShop(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                     @PathVariable UUID id) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<Shop> hit = shops.findById(id);
        if (hit.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Shop s = hit.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", s.getId());
        body.put("merchantId", s.getMerchantId());
        body.put("tenantId", s.getTenantId());
        body.put("status", s.getStatus().name());
        return ResponseEntity.ok(body);
    }

    /**
     * Promote-on-registration webhook. Called by user-service the moment a
     * phone completes signup so every PENDING LoyaltyUser matching that phone
     * — across all tenants — flips to ACTIVE. Idempotent: replays are safe and
     * report how many rows actually changed.
     *
     * <p>Body: {@code {"phoneNumber": "+263771234567"}}.
     */
    @org.springframework.web.bind.annotation.PostMapping("/users/promote")
    @Operation(summary = "(S2S) Promote a customer's PENDING loyalty rows to ACTIVE",
            description = "Webhook from user-service: when a phone completes signup, every " +
                          "PENDING LoyaltyUser row matching that phone (across all tenants) flips " +
                          "to ACTIVE. Idempotent — replays report how many rows actually changed.")
    public ResponseEntity<?> promote(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                     @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String phone = body == null ? null : body.get("phoneNumber");
        if (phone == null || phone.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "phoneNumber is required"));
        }
        int promoted = userService.promoteByPhone(phone);
        log.info("Promoted {} PENDING LoyaltyUser(s) for phone={}", promoted, MsisdnMasking.mask(phone));
        // Tell the customer their accrued points are now spendable. Best-effort,
        // and only when something actually flipped (idempotent replays are silent).
        // Fired here (post-commit of promoteByPhone) rather than inside the service.
        if (promoted > 0) {
            memberNotifier.notifyPointsUnlocked(phone);
        }
        return ResponseEntity.ok(Map.of("phoneNumber", phone, "promoted", promoted));
    }

    /**
     * Shop checkout: optional earn (cash → PURCHASE) and optional burn
     * (points → REDEMPTION), atomic. Called by payment-service when a customer
     * pays at a shop with cash, points, or a mix of both.
     *
     * <p>Body: {@code {"shopId": "...", "phoneNumber": "...",
     * "cashAmount": 10.00, "pointsAmount": 200, "reference": "POS-..."}}.
     * At least one of {@code cashAmount} or {@code pointsAmount} must be > 0.
     */
    @PostMapping("/shop-checkout")
    @Operation(summary = "(S2S) Shop checkout — optional earn + optional burn, atomic",
            description = "Called by payment-service when a customer pays at a shop. The cash leg " +
                          "earns points per the merchant's loyalty rules; the points leg burns " +
                          "from the customer's wallet. Both legs commit in a single loyalty-service " +
                          "transaction. At least one of cashAmount or pointsAmount must be > 0.")
    public ResponseEntity<?> shopCheckout(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                          @RequestBody Dtos.ShopCheckoutInternalRequestDTO body) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            // checkout() still performs all validation (shop existence, at-least-one
            // amount > 0, etc.) and returns the same typed error codes as before —
            // the only change is the request binding (Map -> typed DTO).
            ShopCheckoutService.Result r = shopCheckoutService.checkout(
                    body.shopId(), body.phoneNumber(), body.cashAmount(), body.pointsAmount(), body.reference());

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("shopId", r.shopId());
            resp.put("merchantId", r.merchantId());
            resp.put("tenantId", r.tenantId());
            resp.put("loyaltyUserId", r.loyaltyUserId());
            resp.put("cashAmount", r.cashAmount());
            resp.put("pointsRedeemed", r.pointsRedeemed());
            resp.put("pointsEarned", r.pointsEarned());
            resp.put("walletBalanceAfter", r.walletBalanceAfter());
            resp.put("purchaseTransactionId", r.purchaseTransactionId());
            resp.put("redemptionTransactionId", r.redemptionTransactionId());
            return ResponseEntity.ok(resp);
        } catch (LoyaltyException e) {
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getCode(), "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", "BAD_INPUT", "message", e.getMessage()));
        }
    }

    @GetMapping("/ticketing/rule")
    @Operation(summary = "(S2S) Ticketing earn/redeem rates for an event organizer")
    public ResponseEntity<?> ticketingRule(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                           @RequestParam(value = "organizerUuid", required = false) String organizerUuid) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            TicketingLoyaltyService.TicketingRule r = ticketingLoyaltyService.rule(asUuid(organizerUuid, "organizerUuid"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", r.tenantId());
            body.put("merchantId", r.merchantId());
            body.put("earnRate", r.earnRate());
            body.put("redeemRate", r.redeemRate());
            body.put("currency", r.currency());
            body.put("active", true);
            return ResponseEntity.ok(body);
        } catch (LoyaltyException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getCode(), "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", "BAD_INPUT", "message", e.getMessage()));
        }
    }

    @PostMapping("/ticketing/earn")
    @Operation(summary = "(S2S) Earn points on a ticket purchase (idempotent on reference)")
    public ResponseEntity<?> ticketingEarn(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                           @RequestBody Dtos.TicketingEarnRequestDTO body) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            // earn() validates organizerUuid / phoneNumber / cashAmount and throws
            // typed LoyaltyExceptions (mapped below) — behaviour unchanged; only the
            // request binding moves from an untyped Map to a typed DTO.
            TicketingLoyaltyService.EarnResult r = ticketingLoyaltyService.earn(
                    body.organizerUuid(), body.phoneNumber(), body.cashAmount(), body.reference());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("transactionId", r.transactionId());
            resp.put("merchantId", r.merchantId());
            resp.put("pointsEarned", r.pointsEarned());
            resp.put("balanceAfter", r.balanceAfter());
            resp.put("replayed", r.replayed());
            return ResponseEntity.ok(resp);
        } catch (LoyaltyException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getCode(), "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", "BAD_INPUT", "message", e.getMessage()));
        }
    }

    @PostMapping("/ticketing/redeem")
    @Operation(summary = "(S2S) Redeem points toward a ticket purchase (idempotent on reference)")
    public ResponseEntity<?> ticketingRedeem(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                             @RequestBody Dtos.TicketingRedeemRequestDTO body) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            // redeem() validates organizerUuid / phoneNumber / points and throws
            // typed LoyaltyExceptions (mapped below) — behaviour unchanged; only the
            // request binding moves from an untyped Map to a typed DTO.
            TicketingLoyaltyService.RedeemResult r = ticketingLoyaltyService.redeem(
                    body.organizerUuid(), body.phoneNumber(), body.points(), body.reference());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("transactionId", r.transactionId());
            resp.put("merchantId", r.merchantId());
            resp.put("balanceAfter", r.balanceAfter());
            return ResponseEntity.ok(resp);
        } catch (LoyaltyException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getCode(), "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", "BAD_INPUT", "message", e.getMessage()));
        }
    }

    private static UUID asUuid(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return UUID.fromString(v.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is not a valid UUID");
        }
    }

    private boolean authorized(String presented) {
        if (expectedToken == null || expectedToken.isBlank()) {
            // No token configured: refuse all internal calls rather than fail-open.
            log.warn("Internal API token is not configured; rejecting call");
            return false;
        }
        if (presented == null) {
            return false;
        }
        // Constant-time compare. String.equals exits on the first mismatching
        // byte, leaving a measurable timing oracle on the shared secret —
        // an attacker can derive it byte by byte. MessageDigest.isEqual runs
        // the full comparison even when bytes diverge.
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
