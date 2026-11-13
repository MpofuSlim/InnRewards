package com.innbucks.loyaltyservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.PartnerVoucherDtos;
import com.innbucks.loyaltyservice.exception.LoyaltyDataException;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.security.PartnerRequest;
import com.innbucks.loyaltyservice.service.EligibilityDeferral;
import com.innbucks.loyaltyservice.service.partner.PartnerVoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.innbucks.loyaltyservice.controller.PartnerVoucherSwaggerExamples.*;

/**
 * The partner voucher API (V62): what the InnBucks middleware calls to generate,
 * inquire and redeem vouchers for a merchant it is linked to (first: MWOS).
 *
 * <p>Authentication is done before this controller by
 * {@link com.innbucks.loyaltyservice.security.VoucherPartnerAuthFilter}: the
 * body is a JWS signed by the partner, and what reaches here is the verified
 * {@link PartnerRequest} on the request, never the raw body. Every response is
 * {@code no-store}.
 */
@RestController
@RequestMapping("/loyalty/partner/vouchers")
@Tag(name = "Partner vouchers", description = "Generate, inquire and redeem vouchers through the InnBucks "
        + "middleware, with every request signed by the middleware's key.")
public class PartnerVoucherController {

    private final PartnerVoucherService service;
    private final EligibilityDeferral eligibilityDeferral;
    private final ObjectMapper mapper;
    private final Validator validator;

    public PartnerVoucherController(PartnerVoucherService service, EligibilityDeferral eligibilityDeferral,
                                    ObjectMapper mapper, Validator validator) {
        this.service = service;
        this.eligibilityDeferral = eligibilityDeferral;
        this.mapper = mapper;
        this.validator = validator;
    }

    @PostMapping("/generate")
    @Operation(summary = "Generate a voucher worth what the customer paid",
            description = """
                    The voucher exists only once its value is in the InnBucks holding account. Cash, card \
                    swipe and money the merchant collected itself debit the merchant's float into holding; an \
                    InnBucks code waits for the customer to pay (`202 AWAITING_PAYMENT`). We send the code to \
                    the customer by WhatsApp, with SMS as the fallback. A POS sale's response also carries \
                    it (`voucher.code`) for the till to print on the slip; an ONLINE, APP or API sale's never \
                    does. `channel` is POS, ONLINE, APP or API (a third-party integrator, which follows the \
                    ONLINE rules); cash and card swipe are POS only. \
                    `partnerReference` is the idempotency key: the same reference with the same content returns \
                    the order as it now stands (`replayed: true`), and with different content is \
                    `422 IDEMPOTENCY_MISMATCH`. `validityDays` (optional, 1 to 365 by default) sets how many \
                    days the voucher stays valid from issue; without it the merchant's standard validity \
                    applies. `usage` is SINGLE (the default: must be redeemed for its full value in one go) or \
                    MULTIPLE (spent over several redemptions). `redeemableAt` (optional): left out, the voucher \
                    works at `merchant` only; "ANY", also at every merchant in the issuer's live partnerships \
                    that you serve; merchant codes, also at those, each of which must be such a partner, else \
                    `400 MERCHANT_NOT_A_PARTNER` naming the codes. The list is worked out and fixed when the \
                    order is made; `voucher.redeemableAt` returns every merchant, the issuer first. \
                    `funding` left out: the voucher is PAID AT THE REDEEM (Flow 2). The customer gets the code \
                    now (always in the response and by message) and pays it in full at one of the issuer's \
                    agents, whose redeem carries the payment; no money moves until then. Such a voucher is \
                    SINGLE (`usage: MULTIPLE` is `400 USAGE_NOT_ALLOWED`), works at the issuer only \
                    (`redeemableAt` is `400 REDEEMABLE_AT_NOT_ALLOWED`), and reads `status: ISSUED` with \
                    `voucher.payAtRedeem: true` and `funding.status: AT_REDEEM` until it is paid, then \
                    `voucher.status: REDEEMED` and `funding.status: CONFIRMED`. `channel` may be left out \
                    (ONLINE). An InnBucks code is ONLINE, APP or API only. \
                    On staging the funding is simulated (`funding.simulated: true`); \
                    a reference starting with SIMFAIL-, SIMSLOW-, SIMSLOWFAIL- or SIMDOWN- asks for a refusal, a \
                    slow confirmation, a slow refusal, or an unreachable provider.

                    """ + REQUEST_DESCRIPTION,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/jose",
                            schema = @Schema(type = "string", example = SIGNED_REQUEST))))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Voucher issued (a replay of an issued order answers 200)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Paid at the sale", value = GENERATE_201),
                                    @ExampleObject(name = "Paid at the redeem (no funding)",
                                            value = GENERATE_201_PAY_AT_REDEEM)})),
            @ApiResponse(responseCode = "202", description = "Waiting for the customer to pay, or the payment is still being processed. Poll inquire by partnerReference.",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Awaiting payment (InnBucks code)", value = GENERATE_202_AWAITING),
                                    @ExampleObject(name = "Processing", value = GENERATE_202_PROCESSING)})),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Field errors", value = VALIDATION_400),
                                    @ExampleObject(name = "Validity out of range", value = VALIDITY_400),
                                    @ExampleObject(name = "Payment method not available", value = FUNDING_METHOD_400),
                                    @ExampleObject(name = "Bad phone", value = BAD_PHONE_400),
                                    @ExampleObject(name = "redeemableAt names a merchant that is not a partner",
                                            value = NOT_A_PARTNER_400),
                                    @ExampleObject(name = "Merchant-collected without its details",
                                            value = COLLECTION_DETAILS_400),
                                    @ExampleObject(name = "Paid at the redeem, but MULTIPLE", value = USAGE_NOT_ALLOWED_400),
                                    @ExampleObject(name = "Paid at the redeem, but redeemableAt",
                                            value = REDEEMABLE_AT_NOT_ALLOWED_400)})),
            @ApiResponse(responseCode = "401", description = "PARTNER_AUTH_REJECTED: one answer for every authentication failure",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = AUTH_401))),
            @ApiResponse(responseCode = "403", description = "MERCHANT_NOT_PERMITTED: not a merchant this partner is linked to",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = MERCHANT_403))),
            @ApiResponse(responseCode = "404", description = "The partner API is switched off on this environment",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "A possible double sale, or payment evidence already used",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Possible duplicate", value = DUPLICATE_409),
                                    @ExampleObject(name = "Approval code already used", value = EVIDENCE_409)})),
            @ApiResponse(responseCode = "422", description = "Refused; nothing moved",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Float can't cover it", value = FLOAT_422),
                                    @ExampleObject(name = "Reference reused for a different request", value = MISMATCH_422),
                                    @ExampleObject(name = "Limit", value = LIMIT_422)})),
            @ApiResponse(responseCode = "503", description = "Not recorded (send the same request again), or the provider never received it (use a new reference)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Funding unavailable: nothing recorded", value = FUNDING_503),
                                    @ExampleObject(name = "Provider unreachable: attempt closed", value = PROVIDER_503),
                                    @ExampleObject(name = "Replay check unavailable", value = REPLAY_503)}))
    })
    public ResponseEntity<ApiResult<PartnerVoucherDtos.GenerateResult>> generate(HttpServletRequest request) {
        PartnerRequest caller = caller(request);
        PartnerVoucherDtos.GenerateRequest body = read(caller, PartnerVoucherDtos.GenerateRequest.class);
        PartnerVoucherService.Answer<PartnerVoucherDtos.GenerateResult> answer = service.generate(caller, body);
        return ResponseEntity.status(answer.status())
                .body(ApiResult.<PartnerVoucherDtos.GenerateResult>builder()
                        .code(answer.code()).message(answer.message()).data(answer.data()).build());
    }

    @PostMapping("/inquire")
    @Operation(summary = "Inquire about an order, a voucher or a redemption",
            description = """
                    Read-only. `by` is PARTNER_REFERENCE (what happened to my sale: the generate result as it \
                    now stands, with the code for a POS sale so a slip can be printed again, and always for a \
                    voucher paid at the redeem), CODE (what is this voucher worth, and `payAtRedeem: true` when \
                    the agent must take the payment first; at a merchant it can't be used at, \
                    `403 NOT_REDEEMABLE_HERE`, counted: needs endUserRef, and \
                    endUserPhone when the merchant binds redemption to the holder's phone; unknown codes count \
                    towards the guessing lockout) or REDEMPTION (has the merchant been paid for this use).

                    """ + REQUEST_DESCRIPTION,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/jose",
                            schema = @Schema(type = "string", example = SIGNED_REQUEST))))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "By partnerReference", value = GENERATE_201),
                                    @ExampleObject(name = "By code", value = INQUIRE_CODE_200),
                                    @ExampleObject(name = "By code, paid at the redeem and not paid yet",
                                            value = INQUIRE_CODE_UNPAID_200),
                                    @ExampleObject(name = "By redemption", value = INQUIRE_REDEMPTION_200)})),
            @ApiResponse(responseCode = "400", description = "Invalid request, or a mistyped code (not counted)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Field errors", value = VALIDATION_400),
                                    @ExampleObject(name = "Mistyped code", value = MISTYPED_400)})),
            @ApiResponse(responseCode = "401", description = "PARTNER_AUTH_REJECTED",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = AUTH_401))),
            @ApiResponse(responseCode = "403", description = "Not a linked merchant, or (by code, counted) not usable "
                    + "at this merchant or not the holder's phone",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Merchant not permitted", value = MERCHANT_403),
                                    @ExampleObject(name = "Not usable here", value = NOT_REDEEMABLE_HERE_403),
                                    @ExampleObject(name = "Not the holder", value = NOT_OWNER_403)})),
            @ApiResponse(responseCode = "404", description = "No such order, voucher (counted) or redemption",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Order", value = INQUIRE_ORDER_404),
                                    @ExampleObject(name = "Voucher", value = VOUCHER_404)})),
            @ApiResponse(responseCode = "429", description = "VOUCHER_ATTEMPTS_LOCKED: too many unknown codes for this customer account",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = LOCKED_429)))
    })
    public ResponseEntity<ApiResult<Object>> inquire(HttpServletRequest request) {
        PartnerRequest caller = caller(request);
        PartnerVoucherDtos.InquireRequest body = read(caller, PartnerVoucherDtos.InquireRequest.class);
        PartnerVoucherService.Answer<Object> answer = service.inquire(caller, body);
        return ResponseEntity.status(answer.status())
                .body(ApiResult.builder().code(answer.code()).message(answer.message()).data(answer.data()).build());
    }

    @PostMapping("/redeem")
    @Operation(summary = "Redeem some or all of a voucher on the merchant's platform",
            description = """
                    Takes `amount` off the voucher's balance (all of it when omitted). An `amount` above the \
                    balance is refused with `INSUFFICIENT_VOUCHER_BALANCE`: nothing is spent, and the balance \
                    is in `data.remainingBalance` so you can retry for it. A 200 is final: credit the customer then. The amount is then paid to the merchant from \
                    holding; `settlement` says when. `reference` is the idempotency key: a re-send returns the \
                    original use (`replayed: true`) and never spends twice. A voucher can be redeemed at any \
                    merchant in its `redeemableAt`; that merchant is the one paid and billed for the use. At any \
                    other merchant it is `403 NOT_REDEEMABLE_HERE` (counted toward the lockout). While \
                    InnBucks has suspended the partnership, a use there is `409 PARTNERSHIP_SUSPENDED`.

                    A voucher PAID AT THE REDEEM (`payAtRedeem: true`) is paid here: send `funding` (CASH, \
                    CARD_SWIPE or MERCHANT_COLLECTED; cash and card at a POS only; `accountNumber` the agent's \
                    account) and the full value (or no amount). `merchant` is the issuer, the one paid. The \
                    payment goes from the agent straight to the issuer; a 200 means it is paid and the voucher \
                    is REDEEMED, so credit the customer then. `202 PENDING`: the payment is being processed, ask \
                    with an inquiry by redemption. A refused payment (`422`, `503`) moved nothing; the code can \
                    be paid again under a new reference. Without `funding` it is `400 PAYMENT_REQUIRED`; \
                    `funding` on any other voucher is `400 PAYMENT_NOT_NEEDED`, because it was paid for when it \
                    was sold. On staging the SIMFAIL-, SIMSLOW-, SIMSLOWFAIL- and SIMDOWN- prefixes on \
                    `reference` drive the payment as they drive a generate.

                    """ + REQUEST_DESCRIPTION,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/jose",
                            schema = @Schema(type = "string", example = SIGNED_REQUEST))))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Voucher redeemed (or replayed)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Redeemed", value = REDEEM_200),
                                    @ExampleObject(name = "Paid at the redeem and redeemed",
                                            value = REDEEM_200_PAID_AT_REDEEM)})),
            @ApiResponse(responseCode = "202", description = "Paid at the redeem: the agent's payment is being processed",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = REDEEM_202_PENDING))),
            @ApiResponse(responseCode = "400", description = "Invalid request, mistyped code (not counted), expired, "
                    + "less than the full value of a SINGLE-use voucher, or the payment missing or not wanted",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Field errors", value = VALIDATION_400),
                                    @ExampleObject(name = "Mistyped code", value = MISTYPED_400),
                                    @ExampleObject(name = "Expired", value = EXPIRED_400),
                                    @ExampleObject(name = "Part of a single-use voucher", value = PARTIAL_USE_400),
                                    @ExampleObject(name = "More than the balance", value = INSUFFICIENT_BALANCE_400),
                                    @ExampleObject(name = "Paid at the redeem, no funding", value = PAYMENT_REQUIRED_400),
                                    @ExampleObject(name = "Funding for a voucher already paid for",
                                            value = PAYMENT_NOT_NEEDED_400),
                                    @ExampleObject(name = "Merchant-collected without its details",
                                            value = COLLECTION_DETAILS_400)})),
            @ApiResponse(responseCode = "401", description = "PARTNER_AUTH_REJECTED",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = AUTH_401))),
            @ApiResponse(responseCode = "403", description = "Not a linked merchant, or (counted) not usable at this "
                    + "merchant or not the holder's phone",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Merchant not permitted", value = MERCHANT_403),
                                    @ExampleObject(name = "Not usable here", value = NOT_REDEEMABLE_HERE_403),
                                    @ExampleObject(name = "Not the holder", value = NOT_OWNER_403)})),
            @ApiResponse(responseCode = "404", description = "Unknown code, or not this partner's voucher (counted)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = VOUCHER_404))),
            @ApiResponse(responseCode = "409", description = "Spent, revoked, the reference used on another voucher, "
                    + "or (at a partner merchant) the partnership is suspended by InnBucks",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Spent", value = ALREADY_REDEEMED_409),
                                    @ExampleObject(name = "Partnership suspended", value = PARTNERSHIP_SUSPENDED_409),
                                    @ExampleObject(name = "Payment at the redeem already in progress",
                                            value = PAYMENT_IN_PROGRESS_409)})),
            @ApiResponse(responseCode = "422", description = "The reference was used for a different redemption, or "
                    + "the agent's payment was refused (nothing moved)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Reference reused", value = REDEEM_MISMATCH_422),
                                    @ExampleObject(name = "Agent's float can't cover it", value = REDEEM_FLOAT_422)})),
            @ApiResponse(responseCode = "429", description = "VOUCHER_ATTEMPTS_LOCKED",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = LOCKED_429))),
            @ApiResponse(responseCode = "503", description = "Paid at the redeem: the payment provider never received "
                    + "the payment (nothing moved; use a new reference)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = REDEEM_PROVIDER_503)))
    })
    public ResponseEntity<ApiResult<PartnerVoucherDtos.RedeemResult>> redeem(HttpServletRequest request) {
        PartnerRequest caller = caller(request);
        PartnerVoucherDtos.RedeemRequest body = read(caller, PartnerVoucherDtos.RedeemRequest.class);
        PartnerVoucherService.Answer<PartnerVoucherDtos.RedeemResult> answer =
                eligibilityDeferral.run(() -> service.redeem(caller, body));
        return ResponseEntity.status(answer.status())
                .body(ApiResult.<PartnerVoucherDtos.RedeemResult>builder()
                        .code(answer.code()).message(answer.message()).data(answer.data()).build());
    }

    // ------------------------------------------------------------------

    /** The verified request the filter left; its absence means the filter did not run. */
    private static PartnerRequest caller(HttpServletRequest request) {
        Object verified = request.getAttribute(PartnerRequest.ATTRIBUTE);
        if (!(verified instanceof PartnerRequest partnerRequest)) {
            throw new LoyaltyException(HttpStatus.UNAUTHORIZED, "PARTNER_AUTH_REJECTED", "Request rejected.");
        }
        return partnerRequest;
    }

    /** The signed {@code body} claim as a request object, validated like a {@code @Valid} body. */
    private <T> T read(PartnerRequest caller, Class<T> type) {
        JsonNode body = caller.body();
        if (body == null || !body.isObject()) {
            throw validationFailed(Map.of("body", "must be an object"));
        }
        T value;
        try {
            value = mapper.treeToValue(body, type);
        } catch (Exception unreadable) {
            throw validationFailed(Map.of("body", "has a field of the wrong type"));
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (ConstraintViolation<T> v : violations) {
                fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
            }
            throw validationFailed(fields);
        }
        return value;
    }

    private static LoyaltyDataException validationFailed(Map<String, String> fields) {
        return new LoyaltyDataException(HttpStatus.BAD_REQUEST, "400 BAD_REQUEST", "Validation failed", fields);
    }
}
