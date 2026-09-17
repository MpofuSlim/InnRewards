package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.service.VoucherPurchaseService;
import com.innbucks.loyaltyservice.service.VoucherPurchaseService.InternalOrderView;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S2S surface for payment-service's LOYALTY_VOUCHER order gateway (V47) —
 * the loyalty twin of marketplace-service's internal order endpoints:
 * read the order, extend its payable window past the payment instrument's
 * TTL, confirm the payment (which issues the voucher).
 *
 * <p>Same posture as {@link InternalMerchantLookupController}: shared-secret
 * header with a constant-time compare, {@link Hidden} from public Swagger,
 * and the gateway's {@code loyalty-internal-deny} route already blocks
 * {@code /loyalty/internal/**} at the edge. Responses are plain maps (this
 * service's internal convention), NOT the ApiResult envelope. Amounts are
 * DECIMAL major units here — payment-service's gateway is the major↔minor
 * conversion point, per its OrderGateway contract.
 */
@RestController
@RequestMapping("/loyalty/internal/voucher-orders")
@Slf4j
@Hidden
public class InternalVoucherOrderController {

    private final VoucherPurchaseService purchases;
    private final String expectedToken;

    public InternalVoucherOrderController(VoucherPurchaseService purchases,
                                          @Value("${innbucks.internal-api-token:}") String expectedToken) {
        this.purchases = purchases;
        this.expectedToken = expectedToken;
    }

    @GetMapping("/{orderRef}")
    @Operation(summary = "(S2S) Read a voucher purchase order",
            description = "payment-service resolves the amount to collect, the currency, the payer's phone "
                    + "(the EcoCash prompt target) and whether the order is still payable, BEFORE minting "
                    + "any payment instrument.")
    public ResponseEntity<?> get(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                 @PathVariable String orderRef) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(body(purchases.internalView(orderRef)));
        } catch (LoyaltyException e) {
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getCode(), "message", e.getMessage()));
        }
    }

    @PatchMapping("/{orderRef}/extend-expiry")
    @Operation(summary = "(S2S) Extend the order's payable window (never shortens)",
            description = "Called before a payment code/prompt is minted so the order provably outlives "
                    + "the instrument the customer is shown. 1..60 minutes; refused once the order is no "
                    + "longer awaiting payment.")
    public ResponseEntity<?> extendExpiry(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                          @PathVariable String orderRef,
                                          @RequestParam("minutes") int minutes) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(body(purchases.internalExtendExpiry(orderRef, minutes)));
        } catch (LoyaltyException e) {
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getCode(), "message", e.getMessage()));
        }
    }

    /**
     * Body: {@code {"paymentRef": "...", "amountCents": 500}}. Idempotent by
     * paymentRef; the cents cross-check against the order's frozen amount is
     * the 100x guard's confirm leg. Confirmation ISSUES the voucher in the
     * same transaction (recipient + sender notifications ride along), so a
     * 200 here means the voucher exists.
     */
    @PatchMapping("/{orderRef}/confirm-payment")
    @Operation(summary = "(S2S) Confirm the payment and issue the voucher")
    public ResponseEntity<?> confirmPayment(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                            @PathVariable String orderRef,
                                            @RequestBody Map<String, Object> req) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            String paymentRef = req == null || req.get("paymentRef") == null
                    ? null : req.get("paymentRef").toString();
            long amountCents = req == null ? 0L : asLong(req.get("amountCents"));
            return ResponseEntity.ok(body(purchases.internalConfirmPayment(orderRef, paymentRef, amountCents)));
        } catch (LoyaltyException e) {
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getCode(), "message", e.getMessage()));
        }
    }

    private static Map<String, Object> body(InternalOrderView v) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("orderRef", v.orderRef());
        map.put("status", v.status());
        map.put("amount", v.amount());
        map.put("currency", v.currency());
        map.put("payerMsisdn", v.payerMsisdn());
        map.put("expiresAt", v.expiresAt());
        map.put("payable", v.payable());
        return map;
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return 0L;
        try {
            return new java.math.BigDecimal(value.toString()).longValueExact();
        } catch (RuntimeException e) {
            return 0L;
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
        // Constant-time compare — same rationale as InternalMerchantLookupController.
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
